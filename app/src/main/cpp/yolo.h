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

// YOLO11 推理封装（CPU）
class Yolo {
public:
    Yolo() = default;
    ~Yolo() = default;

    int load(AAssetManager* mgr, const char* param_name, const char* bin_name, int num_threads);

    int detect(const unsigned char* rgba, int img_w, int img_h,
               std::vector<Object>& objects,
               float prob_threshold = 0.25f, float nms_threshold = 0.45f);

    int input_size() const { return input_size_; }
    int num_class() const { return num_class_; }

private:
    ncnn::Net net_;
    bool ready_ = false;
    int input_size_ = 640;
    int num_class_ = 80;

    static void qsort_descent_inplace(std::vector<Object>& objects, int left, int right);
    static void qsort_descent_inplace(std::vector<Object>& objects);
    static void nms_sorted_bboxes(const std::vector<Object>& objects,
                                  std::vector<int>& picked, float nms_threshold);
};

#endif // YOLO_H
