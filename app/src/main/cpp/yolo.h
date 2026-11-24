#ifndef YOLO_H
#define YOLO_H

#include <android/asset_manager.h>
#include <net.h>

#include <vector>

struct Object {
    float x;      // 左上角 x（原图像素坐标）
    float y;      // 左上角 y（原图像素坐标）
    float w;      // 宽
    float h;      // 高
    int label;
    float prob;
};

// 检测模型运行参数，内置模型与导入模型共用
struct YoloConfig {
    int input_w = 640;
    int input_h = 640;
    int num_class = 80;
    float scale = 1.f / 255.f;      // 像素先乘以该系数
    float mean[3] = {0.f, 0.f, 0.f};  // 归一化后的均值（0~1）
    float std[3] = {1.f, 1.f, 1.f};   // 归一化后的标准差
    float pad_value = 114.f;          // letterbox 填充值（0~255）
    float prob_threshold = 0.25f;
    float nms_threshold = 0.45f;
};

// YOLO 检测推理封装（CPU）
class Yolo {
public:
    Yolo() = default;
    ~Yolo() = default;

    // 从 APK assets 加载（内置模型）
    int load(AAssetManager* mgr, const char* param_name, const char* bin_name,
             int num_threads, const YoloConfig& config);

    // 从文件系统加载（导入模型）
    int loadFromFile(const char* param_path, const char* bin_path,
                     int num_threads, const YoloConfig& config);

    int detect(const unsigned char* rgba, int img_w, int img_h,
               std::vector<Object>& objects,
               float prob_threshold = -1.f, float nms_threshold = -1.f);

    int input_size() const { return config_.input_w; }
    int num_class() const { return config_.num_class; }

private:
    ncnn::Net net_;
    bool ready_ = false;
    YoloConfig config_;

    int applyConfig(int num_threads, const YoloConfig& config);

    static void qsort_descent_inplace(std::vector<Object>& objects, int left, int right);
    static void qsort_descent_inplace(std::vector<Object>& objects);
    static void nms_sorted_bboxes(const std::vector<Object>& objects,
                                  std::vector<int>& picked, float nms_threshold);
};

#endif // YOLO_H
