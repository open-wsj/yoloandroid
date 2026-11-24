#ifndef YOLO_CLS_H
#define YOLO_CLS_H

#include <android/asset_manager.h>
#include <net.h>

#include <utility>
#include <vector>

// 分类模型运行参数
struct YoloClsConfig {
    int input_size = 224;
    int num_class = 1000;
    float scale = 1.f / 255.f;
    float mean[3] = {0.f, 0.f, 0.f};
    float std[3] = {1.f, 1.f, 1.f};
    bool apply_softmax = false;  // 模型输出为 logits 时需要
};

// YOLO-cls 图像分类（CPU）
class YoloCls {
public:
    YoloCls() = default;
    ~YoloCls() = default;

    // 从 APK assets 加载（内置模型）
    int load(AAssetManager* mgr, const char* param_name, const char* bin_name,
             int num_threads, const YoloClsConfig& config);

    // 从文件系统加载（导入模型）
    int loadFromFile(const char* param_path, const char* bin_path,
                     int num_threads, const YoloClsConfig& config);

    // 输入 RGBA 像素，返回按概率降序的 (类别 id, 概率)
    int classify(const unsigned char* rgba, int img_w, int img_h,
                 std::vector<std::pair<int, float>>& results, int topk = 5);

    int input_size() const { return config_.input_size; }
    int num_class() const { return config_.num_class; }

private:
    ncnn::Net net_;
    bool ready_ = false;
    YoloClsConfig config_;

    void applyConfig(int num_threads, const YoloClsConfig& config);
};

#endif // YOLO_CLS_H
