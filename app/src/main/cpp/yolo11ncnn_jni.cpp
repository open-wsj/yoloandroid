#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

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

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_yolo11ncnn_Yolo11Detector_nativeCreate(JNIEnv* env, jobject /*thiz*/,
                                                        jobject asset_manager,
                                                        jstring param_name,
                                                        jstring bin_name,
                                                        jint num_threads) {
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }

    Yolo* yolo = new Yolo();
    std::string param = jstring_to_string(env, param_name);
    std::string bin = jstring_to_string(env, bin_name);

    int ret = yolo->load(mgr, param.c_str(), bin.c_str(), num_threads);
    if (ret != 0) {
        LOGE("load model failed, ret=%d", ret);
        delete yolo;
        return 0;
    }

    LOGI("model loaded: %s, threads=%d", param.c_str(), num_threads);
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

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_yolo11ncnn_Classifier_nativeCreate(JNIEnv* env, jobject /*thiz*/,
                                                    jobject asset_manager,
                                                    jstring param_name,
                                                    jstring bin_name,
                                                    jint num_threads) {
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr) {
        LOGE("AAssetManager_fromJava failed");
        return 0;
    }

    YoloCls* cls = new YoloCls();
    std::string param = jstring_to_string(env, param_name);
    std::string bin = jstring_to_string(env, bin_name);

    int ret = cls->load(mgr, param.c_str(), bin.c_str(), num_threads);
    if (ret != 0) {
        LOGE("load cls model failed, ret=%d", ret);
        delete cls;
        return 0;
    }

    LOGI("cls model loaded: %s, threads=%d", param.c_str(), num_threads);
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
