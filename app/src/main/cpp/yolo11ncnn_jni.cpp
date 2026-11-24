#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

#include <vector>

#include "yolo.h"
#include "yolo_cls.h"

#define TAG "YOLO11-NCNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::string jstring_to_string(JNIEnv* env, jstring s) {
    if (!s)
        return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string str(chars ? chars : "");
    if (chars)
        env->ReleaseStringUTFChars(s, chars);
    return str;
}

static bool read_floats(JNIEnv* env, jfloatArray array, float* out, int count) {
    if (!array || env->GetArrayLength(array) != count)
        return false;
    env->GetFloatArrayRegion(array, 0, count, out);
    return true;
}

// ---------------- 检测 ----------------

static YoloConfig build_detect_config(const int* ints, const float* f) {
    // ints: [numClasses, inputW, inputH]
    // f:    [scale, meanR, meanG, meanB, stdR, stdG, stdB, pad, prob, nms]
    YoloConfig config;
    config.num_class = ints[0];
    config.input_w = ints[1];
    config.input_h = ints[2];
    config.scale = f[0];
    for (int i = 0; i < 3; i++) {
        config.mean[i] = f[1 + i];
        config.std[i] = f[4 + i];
    }
    config.pad_value = f[7];
    config.prob_threshold = f[8];
    config.nms_threshold = f[9];
    return config;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_yolo11ncnn_Yolo11Detector_nativeCreate(JNIEnv* env, jobject /*thiz*/,
                                                        jobject asset_manager,
                                                        jstring param_name,
                                                        jstring bin_name,
                                                        jintArray ints,
                                                        jfloatArray floats,
                                                        jint num_threads) {
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }
    if (!ints || env->GetArrayLength(ints) != 3) {
        LOGE("invalid int config");
        return 0;
    }
    int int_values[3];
    env->GetIntArrayRegion(ints, 0, 3, int_values);
    float float_values[10];
    if (!read_floats(env, floats, float_values, 10)) {
        LOGE("invalid float config");
        return 0;
    }

    Yolo* yolo = new Yolo();
    std::string param = jstring_to_string(env, param_name);
    std::string bin = jstring_to_string(env, bin_name);

    int ret = yolo->load(mgr, param.c_str(), bin.c_str(), num_threads,
                         build_detect_config(int_values, float_values));
    if (ret != 0) {
        LOGE("load model failed, ret=%d", ret);
        delete yolo;
        return 0;
    }

    LOGI("model loaded: %s, threads=%d, classes=%d", param.c_str(), num_threads, int_values[0]);
    return reinterpret_cast<jlong>(yolo);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_yolo11ncnn_Yolo11Detector_nativeCreateFromFile(JNIEnv* env, jobject /*thiz*/,
                                                                jstring param_path,
                                                                jstring bin_path,
                                                                jintArray ints,
                                                                jfloatArray floats,
                                                                jint num_threads) {
    if (!ints || env->GetArrayLength(ints) != 3) {
        LOGE("invalid int config");
        return 0;
    }
    int int_values[3];
    env->GetIntArrayRegion(ints, 0, 3, int_values);
    float float_values[10];
    if (!read_floats(env, floats, float_values, 10)) {
        LOGE("invalid float config");
        return 0;
    }

    Yolo* yolo = new Yolo();
    std::string param = jstring_to_string(env, param_path);
    std::string bin = jstring_to_string(env, bin_path);

    int ret = yolo->loadFromFile(param.c_str(), bin.c_str(), num_threads,
                                 build_detect_config(int_values, float_values));
    if (ret != 0) {
        LOGE("load model from file failed, ret=%d", ret);
        delete yolo;
        return 0;
    }

    LOGI("model loaded from file: %s, threads=%d, classes=%d", param.c_str(), num_threads, int_values[0]);
    return reinterpret_cast<jlong>(yolo);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_yolo11ncnn_Yolo11Detector_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jlong handle) {
    Yolo* yolo = reinterpret_cast<Yolo*>(handle);
    delete yolo;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_yolo11ncnn_Yolo11Detector_nativeDetect(JNIEnv* env, jobject /*thiz*/,
                                                        jlong handle,
                                                        jbyteArray rgba, jint width, jint height,
                                                        jfloat prob_threshold, jfloat nms_threshold) {
    Yolo* yolo = reinterpret_cast<Yolo*>(handle);
    if (!yolo || !rgba)
        return nullptr;

    jbyte* bytes = env->GetByteArrayElements(rgba, nullptr);
    if (!bytes)
        return nullptr;

    std::vector<Object> objects;
    int ret = yolo->detect(reinterpret_cast<const unsigned char*>(bytes),
                           width, height, objects, prob_threshold, nms_threshold);

    env->ReleaseByteArrayElements(rgba, bytes, JNI_ABORT);

    if (ret != 0) {
        LOGE("detect failed, ret=%d", ret);
        return nullptr;
    }

    const int stride = 6;
    jfloatArray result = env->NewFloatArray((jsize) objects.size() * stride);
    if (!result)
        return nullptr;

    std::vector<float> flat(objects.size() * stride);
    for (size_t i = 0; i < objects.size(); i++) {
        flat[i * stride + 0] = objects[i].x;
        flat[i * stride + 1] = objects[i].y;
        flat[i * stride + 2] = objects[i].x + objects[i].w;
        flat[i * stride + 3] = objects[i].y + objects[i].h;
        flat[i * stride + 4] = objects[i].prob;
        flat[i * stride + 5] = (float) objects[i].label;
    }

    env->SetFloatArrayRegion(result, 0, (jsize) flat.size(), flat.data());
    return result;
}

// ---------------- 分类 ----------------

static YoloClsConfig build_cls_config(const int* ints, const float* f) {
    // ints: [numClasses, inputSize]
    // f:    [scale, meanR, meanG, meanB, stdR, stdG, stdB, applySoftmax]
    YoloClsConfig config;
    config.num_class = ints[0];
    config.input_size = ints[1];
    config.scale = f[0];
    for (int i = 0; i < 3; i++) {
        config.mean[i] = f[1 + i];
        config.std[i] = f[4 + i];
    }
    config.apply_softmax = f[7] > 0.5f;
    return config;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_yolo11ncnn_Classifier_nativeCreate(JNIEnv* env, jobject /*thiz*/,
                                                    jobject asset_manager,
                                                    jstring param_name,
                                                    jstring bin_name,
                                                    jintArray ints,
                                                    jfloatArray floats,
                                                    jint num_threads) {
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }
    if (!ints || env->GetArrayLength(ints) != 2) {
        LOGE("invalid int config");
        return 0;
    }
    int int_values[2];
    env->GetIntArrayRegion(ints, 0, 2, int_values);
    float float_values[8];
    if (!read_floats(env, floats, float_values, 8)) {
        LOGE("invalid float config");
        return 0;
    }

    YoloCls* cls = new YoloCls();
    std::string param = jstring_to_string(env, param_name);
    std::string bin = jstring_to_string(env, bin_name);

    int ret = cls->load(mgr, param.c_str(), bin.c_str(), num_threads,
                        build_cls_config(int_values, float_values));
    if (ret != 0) {
        LOGE("load cls model failed, ret=%d", ret);
        delete cls;
        return 0;
    }

    LOGI("cls model loaded: %s, threads=%d, classes=%d", param.c_str(), num_threads, int_values[0]);
    return reinterpret_cast<jlong>(cls);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_yolo11ncnn_Classifier_nativeCreateFromFile(JNIEnv* env, jobject /*thiz*/,
                                                            jstring param_path,
                                                            jstring bin_path,
                                                            jintArray ints,
                                                            jfloatArray floats,
                                                            jint num_threads) {
    if (!ints || env->GetArrayLength(ints) != 2) {
        LOGE("invalid int config");
        return 0;
    }
    int int_values[2];
    env->GetIntArrayRegion(ints, 0, 2, int_values);
    float float_values[8];
    if (!read_floats(env, floats, float_values, 8)) {
        LOGE("invalid float config");
        return 0;
    }

    YoloCls* cls = new YoloCls();
    std::string param = jstring_to_string(env, param_path);
    std::string bin = jstring_to_string(env, bin_path);

    int ret = cls->loadFromFile(param.c_str(), bin.c_str(), num_threads,
                                build_cls_config(int_values, float_values));
    if (ret != 0) {
        LOGE("load cls model from file failed, ret=%d", ret);
        delete cls;
        return 0;
    }

    LOGI("cls model loaded from file: %s, threads=%d, classes=%d", param.c_str(), num_threads, int_values[0]);
    return reinterpret_cast<jlong>(cls);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_yolo11ncnn_Classifier_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/,
                                                     jlong handle) {
    YoloCls* cls = reinterpret_cast<YoloCls*>(handle);
    delete cls;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_yolo11ncnn_Classifier_nativeClassify(JNIEnv* env, jobject /*thiz*/,
                                                      jlong handle,
                                                      jbyteArray rgba, jint width, jint height,
                                                      jint topk) {
    YoloCls* cls = reinterpret_cast<YoloCls*>(handle);
    if (!cls || !rgba)
        return nullptr;

    jbyte* bytes = env->GetByteArrayElements(rgba, nullptr);
    if (!bytes)
        return nullptr;

    std::vector<std::pair<int, float>> results;
    int ret = cls->classify(reinterpret_cast<const unsigned char*>(bytes),
                            width, height, results, topk > 0 ? topk : 5);

    env->ReleaseByteArrayElements(rgba, bytes, JNI_ABORT);

    if (ret != 0) {
        LOGE("classify failed, ret=%d", ret);
        return nullptr;
    }

    const int stride = 2;
    std::vector<float> flat(results.size() * stride);
    for (size_t i = 0; i < results.size(); i++) {
        flat[i * stride + 0] = (float) results[i].first;
        flat[i * stride + 1] = results[i].second;
    }

    jfloatArray result = env->NewFloatArray((jsize) flat.size());
    if (!result)
        return nullptr;

    env->SetFloatArrayRegion(result, 0, (jsize) flat.size(), flat.data());
    return result;
}
