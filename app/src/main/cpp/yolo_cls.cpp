#include "yolo_cls.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

void YoloCls::applyConfig(int num_threads, const YoloClsConfig& config) {
    config_ = config;
    if (config_.input_size <= 0) {
        config_.input_size = 224;
    }
    if (config_.num_class <= 0) {
        config_.num_class = 1000;
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
}

int YoloCls::load(AAssetManager* mgr, const char* param_name, const char* bin_name,
                  int num_threads, const YoloClsConfig& config) {
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

int YoloCls::loadFromFile(const char* param_path, const char* bin_path,
                          int num_threads, const YoloClsConfig& config) {
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

int YoloCls::classify(const unsigned char* rgba, int img_w, int img_h,
                      std::vector<std::pair<int, float>>& results, int topk) {
    results.clear();
    if (!ready_ || !rgba || img_w <= 0 || img_h <= 0)
        return -1;

    // 与 YOLO 分类训练一致：先中心裁成正方形，再缩放到输入尺寸
    const int side = std::min(img_w, img_h);
    const int crop_x = (img_w - side) / 2;
    const int crop_y = (img_h - side) / 2;
    const unsigned char* crop = rgba + ((size_t) crop_y * img_w + crop_x) * 4;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
            crop, ncnn::Mat::PIXEL_RGBA2RGB, side, side, img_w * 4,
            config_.input_size, config_.input_size);

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

    // 输出可能为单通道 (num_class, 1, 1)，也可能为多 channel (1, 1, num_class)，
    // 多 channel 时每通道有对齐填充，必须逐通道读取。
    const int n = out.w * out.h * out.c;
    if (n <= 0)
        return -4;
    if (n != config_.num_class) {
        fprintf(stderr, "unexpected output size: %d, expected %d\n", n, config_.num_class);
        return -5;
    }

    std::vector<float> scores(n);
    if (out.c > 1 && out.w * out.h == 1) {
        for (int i = 0; i < n; i++)
            scores[i] = out.channel(i)[0];
    } else {
        const float* src = out.channel(0);
        std::copy(src, src + n, scores.begin());
    }

    // 有些模型导出时未带 Softmax，需要在后处理中补上
    if (config_.apply_softmax) {
        float max_v = scores[0];
        for (int i = 1; i < n; i++)
            max_v = std::max(max_v, scores[i]);
        float sum = 0.f;
        for (int i = 0; i < n; i++) {
            scores[i] = std::exp(scores[i] - max_v);
            sum += scores[i];
        }
        if (sum > 0.f) {
            for (int i = 0; i < n; i++)
                scores[i] /= sum;
        }
    }

    std::vector<int> order(n);
    for (int i = 0; i < n; i++)
        order[i] = i;

    const int k = std::min(topk, n);
    std::partial_sort(order.begin(), order.begin() + k, order.end(),
                      [&scores](int a, int b) { return scores[a] > scores[b]; });

    results.reserve(k);
    for (int i = 0; i < k; i++)
        results.emplace_back(order[i], scores[order[i]]);

    return 0;
}
