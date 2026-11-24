#include "yolo.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

static inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static inline float intersection_area(const Object& a, const Object& b) {
    float x1 = std::max(a.x, b.x);
    float y1 = std::max(a.y, b.y);
    float x2 = std::min(a.x + a.w, b.x + b.w);
    float y2 = std::min(a.y + a.h, b.y + b.h);
    float w = x2 - x1;
    float h = y2 - y1;
    if (w <= 0.f || h <= 0.f)
        return 0.f;
    return w * h;
}

void Yolo::qsort_descent_inplace(std::vector<Object>& objects, int left, int right) {
    int i = left;
    int j = right;
    float p = objects[(left + right) / 2].prob;

    while (i <= j) {
        while (objects[i].prob > p)
            i++;
        while (objects[j].prob < p)
            j--;
        if (i <= j) {
            std::swap(objects[i], objects[j]);
            i++;
            j--;
        }
    }

    if (left < j)
        qsort_descent_inplace(objects, left, j);
    if (i < right)
        qsort_descent_inplace(objects, i, right);
}

void Yolo::qsort_descent_inplace(std::vector<Object>& objects) {
    if (objects.empty())
        return;
    qsort_descent_inplace(objects, 0, (int) objects.size() - 1);
}

void Yolo::nms_sorted_bboxes(const std::vector<Object>& objects,
                             std::vector<int>& picked, float nms_threshold) {
    picked.clear();
    const int n = (int) objects.size();
    std::vector<float> areas(n);
    for (int i = 0; i < n; i++)
        areas[i] = objects[i].w * objects[i].h;

    for (int i = 0; i < n; i++) {
        const Object& a = objects[i];
        int keep = 1;
        for (int j = 0; j < (int) picked.size(); j++) {
            const Object& b = objects[picked[j]];
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[picked[j]] - inter_area;
            if (inter_area / union_area > nms_threshold) {
                keep = 0;
                break;
            }
        }
        if (keep)
            picked.push_back(i);
    }
}

int Yolo::applyConfig(int num_threads, const YoloConfig& config) {
    config_ = config;
    if (config_.input_w <= 0 || config_.input_h <= 0) {
        config_.input_w = 640;
        config_.input_h = 640;
    }
    if (config_.num_class <= 0) {
        config_.num_class = 80;
    }
    if (config_.scale <= 0.f) {
        config_.scale = 1.f / 255.f;
    }
    for (int i = 0; i < 3; i++) {
        if (config_.std[i] <= 0.f) {
            config_.std[i] = 1.f;
        }
    }

    net_.opt.use_vulkan_compute = false;
    net_.opt.num_threads = num_threads > 0 ? num_threads : 4;
    return 0;
}

int Yolo::load(AAssetManager* mgr, const char* param_name, const char* bin_name,
               int num_threads, const YoloConfig& config) {
    if (!mgr) {
        fprintf(stderr, "asset manager is null\n");
        return -1;
    }

    applyConfig(num_threads, config);

    int ret = net_.load_param(mgr, param_name);
    if (ret != 0) {
        fprintf(stderr, "load_param failed: %s\n", param_name);
        return ret;
    }

    ret = net_.load_model(mgr, bin_name);
    if (ret != 0) {
        fprintf(stderr, "load_model failed: %s\n", bin_name);
        return ret;
    }

    ready_ = true;
    return 0;
}

int Yolo::loadFromFile(const char* param_path, const char* bin_path,
                       int num_threads, const YoloConfig& config) {
    if (!param_path || !bin_path) {
        return -1;
    }

    applyConfig(num_threads, config);

    int ret = net_.load_param(param_path);
    if (ret != 0) {
        fprintf(stderr, "load_param failed: %s\n", param_path);
        return ret;
    }

    ret = net_.load_model(bin_path);
    if (ret != 0) {
        fprintf(stderr, "load_model failed: %s\n", bin_path);
        return ret;
    }

    ready_ = true;
    return 0;
}

int Yolo::detect(const unsigned char* rgba, int img_w, int img_h,
                 std::vector<Object>& objects, float prob_threshold, float nms_threshold) {
    objects.clear();
    if (!ready_ || !rgba || img_w <= 0 || img_h <= 0)
        return -1;

    if (prob_threshold < 0.f)
        prob_threshold = config_.prob_threshold;
    if (nms_threshold < 0.f)
        nms_threshold = config_.nms_threshold;

    const int target_w = config_.input_w;
    const int target_h = config_.input_h;

    // letterbox：保持宽高比，空白处填充 pad_value
    float scale = std::min((float) target_w / img_w, (float) target_h / img_h);
    int resized_w = (int) (img_w * scale + 0.5f);
    int resized_h = (int) (img_h * scale + 0.5f);
    int pad_x = (target_w - resized_w) / 2;
    int pad_y = (target_h - resized_h) / 2;

    ncnn::Mat resized = ncnn::Mat::from_pixels_resize(
            rgba, ncnn::Mat::PIXEL_RGBA2RGB, img_w, img_h, resized_w, resized_h);

    ncnn::Mat in;
    ncnn::copy_make_border(resized, in, pad_y, target_h - resized_h - pad_y,
                           pad_x, target_w - resized_w - pad_x,
                           ncnn::BORDER_CONSTANT, config_.pad_value);

    // (v * scale - mean) / std，转换为 ncnn 的 (v - mean') * norm' 形式
    float mean_vals[3];
    float norm_vals[3];
    for (int i = 0; i < 3; i++) {
        mean_vals[i] = config_.mean[i] / config_.scale;
        norm_vals[i] = config_.scale / config_.std[i];
    }
    in.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = net_.create_extractor();

    const std::vector<const char*>& input_names = net_.input_names();
    const std::vector<const char*>& output_names = net_.output_names();
    if (input_names.empty() || output_names.empty())
        return -2;

    ex.input(input_names[0], in);

    ncnn::Mat out;
    ex.extract(output_names[0], out);
    if (out.empty())
        return -3;

    // Ultralytics YOLO 输出为 (4 + num_class) x anchors
    // w == num_attrs 时每个 anchor 的属性连续存放，否则按属性行存放
    const int num_attrs = 4 + config_.num_class;
    const bool attr_major = (out.w == num_attrs);
    const int num_anchors = attr_major ? out.h : out.w;
    // 结构自检：输出属性维度必须与类别数匹配，否则说明模型与 manifest 不一致
    if ((!attr_major && out.h != num_attrs) || num_anchors <= 0) {
        fprintf(stderr, "unexpected output shape: w=%d h=%d c=%d, attrs=%d\n",
                out.w, out.h, out.c, num_attrs);
        return -5;
    }

    const float* base = out.channel(0);

    std::vector<Object> candidates;
    candidates.reserve(256);

    for (int a = 0; a < num_anchors; a++) {
        auto val = [&](int attr) -> float {
            if (attr_major)
                return base[(size_t) a * num_attrs + attr];
            return base[(size_t) attr * num_anchors + a];
        };

        float cx = val(0);
        float cy = val(1);
        float bw = val(2);
        float bh = val(3);

        int label = -1;
        float score = 0.f;
        for (int c = 0; c < config_.num_class; c++) {
            float s = val(4 + c);
            if (s > score) {
                score = s;
                label = c;
            }
        }

        if (score < prob_threshold || label < 0)
            continue;

        // letterbox 还原到原图坐标
        float x1 = (cx - bw * 0.5f - pad_x) / scale;
        float y1 = (cy - bh * 0.5f - pad_y) / scale;
        float x2 = (cx + bw * 0.5f - pad_x) / scale;
        float y2 = (cy + bh * 0.5f - pad_y) / scale;

        x1 = clampf(x1, 0.f, (float) img_w - 1.f);
        y1 = clampf(y1, 0.f, (float) img_h - 1.f);
        x2 = clampf(x2, 0.f, (float) img_w - 1.f);
        y2 = clampf(y2, 0.f, (float) img_h - 1.f);
        if (x2 <= x1 || y2 <= y1)
            continue;

        Object obj;
        obj.x = x1;
        obj.y = y1;
        obj.w = x2 - x1;
        obj.h = y2 - y1;
        obj.label = label;
        obj.prob = score;
        candidates.push_back(obj);
    }

    qsort_descent_inplace(candidates);

    std::vector<int> picked;
    nms_sorted_bboxes(candidates, picked, nms_threshold);

    objects.reserve(picked.size());
    for (int idx : picked)
        objects.push_back(candidates[idx]);

    return 0;
}
