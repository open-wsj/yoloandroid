#ifndef YOLO_CLS_H
#define YOLO_CLS_H

#include <android/asset_manager.h>
#include <net.h>

#include <utility>
#include <vector>

// YOLO11-cls 图像分类（CPU）
class YoloCls {
public:
    YoloCls() = default;
    ~YoloCls() = default;

    int load(AAssetManager* mgr, const char* param_name, const char* bin_name, int num_threads);

    // 输入 RGBA 像素，返回按概率降序的 (类别 id, 概率)
    int classify(const unsigned char* rgba, int img_w, int img_h,
                 std::vector<std::pair<int, float>>& results, int topk = 5);

    int input_size() const { return input_size_; }
    int num_class() const { return num_class_; }

private:
    ncnn::Net net_;
    bool ready_ = false;
    int input_size_ = 224;
    int num_class_ = 1000;
};

#endif // YOLO_CLS_H
