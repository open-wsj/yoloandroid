#include "yolo_cls.h"

#include <algorithm>
#include <cstdio>

int YoloCls::load(AAssetManager* mgr, const char* param_name, const char* bin_name,
                  int num_threads) {
    if (!mgr) {
        fprintf(stderr, "asset manager is null\n");
        return -1;
    }

    net_.opt.use_vulkan_compute = false;
    net_.opt.num_threads = num_threads > 0 ? num_threads : 4;

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

int YoloCls::classify(const unsigned char* rgba, int img_w, int img_h,
                      std::vector<std::pair<int, float>>& results, int topk) {
    results.clear();
    if (!ready_ || !rgba || img_w <= 0 || img_h <= 0)
        return -1;

    // 与 YOLO 分类训练一致：先中心裁成正方形，再缩放到 224
    const int side = std::min(img_w, img_h);
    const int crop_x = (img_w - side) / 2;
    const int crop_y = (img_h - side) / 2;
    const unsigned char* crop = rgba + ((size_t) crop_y * img_w + crop_x) * 4;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
            crop, ncnn::Mat::PIXEL_RGBA2RGB, side, side, input_size_, input_size_);

    const float norm_vals[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    in.substract_mean_normalize(0, norm_vals);

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

    // 导出的模型末尾已带 Softmax，out 即各类别概率
    const int n = out.w * out.h * out.c;
    if (n <= 0)
        return -4;

    const float* scores = out.channel(0);
    std::vector<int> order(n);
    for (int i = 0; i < n; i++)
        order[i] = i;

    const int k = std::min(topk, n);
    std::partial_sort(order.begin(), order.begin() + k, order.end(),
                      [scores](int a, int b) { return scores[a] > scores[b]; });

    results.reserve(k);
    for (int i = 0; i < k; i++)
        results.emplace_back(order[i], scores[order[i]]);

    return 0;
}
