package com.example.yolo11ncnn;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 模型运行参数：内置模型与导入模型共用，字段来自模型包内的 manifest.json。
 */
public class ModelSpec {

    public static final String TASK_DETECT = "detect";
    public static final String TASK_CLASSIFY = "classify";

    public static final String POST_YOLO_ANCHOR = "yolo_anchor";
    public static final String POST_SOFTMAX_TOPK = "softmax_topk";

    public int schemaVersion = 1;
    public String id = "model";
    public String name = "模型";
    public String version = "1.0";
    public String task = TASK_DETECT;

    public int inputW = 640;
    public int inputH = 640;
    public float scale = 1f / 255f;
    public float[] mean = {0f, 0f, 0f};
    public float[] std = {1f, 1f, 1f};
    public float padValue = 114f;

    public int numClasses = 80;
    public String labelsFile = "labels.txt";
    public String paramFile = "model.ncnn.param";
    public String binFile = "model.ncnn.bin";

    public String postType = POST_YOLO_ANCHOR;
    public boolean applySoftmax = false;
    public float probThreshold = 0.25f;
    public float nmsThreshold = 0.45f;
    public int topk = 5;

    public boolean isDetect() {
        return TASK_DETECT.equals(task);
    }

    /** 内置检测模型（assets 中的 YOLO26m）。 */
    public static ModelSpec builtinDetect() {
        ModelSpec spec = new ModelSpec();
        spec.id = "builtin-detect";
        spec.name = "内置 YOLO26m";
        spec.version = "1.0";
        spec.task = TASK_DETECT;
        spec.inputW = 640;
        spec.inputH = 640;
        spec.numClasses = 80;
        spec.labelsFile = "labels.txt";
        spec.paramFile = "yolo26m.ncnn.param";
        spec.binFile = "yolo26m.ncnn.bin";
        spec.postType = POST_YOLO_ANCHOR;
        return spec;
    }

    /** 内置分类模型（assets 中的 YOLO26m-cls）。 */
    public static ModelSpec builtinClassify() {
        ModelSpec spec = new ModelSpec();
        spec.id = "builtin-classify";
        spec.name = "内置 YOLO26m-cls";
        spec.version = "1.0";
        spec.task = TASK_CLASSIFY;
        spec.inputW = 224;
        spec.inputH = 224;
        spec.numClasses = 1000;
        spec.labelsFile = "imagenet_labels.txt";
        spec.paramFile = "yolo26m-cls.ncnn.param";
        spec.binFile = "yolo26m-cls.ncnn.bin";
        spec.postType = POST_SOFTMAX_TOPK;
        spec.applySoftmax = false;
        return spec;
    }

    public static ModelSpec fromJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        ModelSpec spec = new ModelSpec();
        spec.schemaVersion = root.optInt("schemaVersion", 1);
        spec.id = root.optString("id", "model");
        spec.name = root.optString("name", spec.id);
        spec.version = root.optString("version", "1.0");
        spec.task = root.optString("task", TASK_DETECT);

        JSONObject input = root.optJSONObject("input");
        if (input != null) {
            spec.inputW = input.optInt("width", spec.isDetect() ? 640 : 224);
            spec.inputH = input.optInt("height", spec.inputW);
            spec.scale = (float) input.optDouble("scale", 1.0 / 255.0);
            spec.mean = readTriple(input.optJSONArray("mean"), 0f);
            spec.std = readTriple(input.optJSONArray("std"), 1f);
            spec.padValue = (float) input.optDouble("padValue", 114.0);
        }

        JSONObject classes = root.optJSONObject("classes");
        if (classes != null) {
            spec.numClasses = classes.optInt("numClasses", spec.isDetect() ? 80 : 1000);
            spec.labelsFile = classes.optString("labelsFile", "labels.txt");
        }

        JSONObject files = root.optJSONObject("files");
        if (files != null) {
            spec.paramFile = files.optString("param", "model.ncnn.param");
            spec.binFile = files.optString("bin", "model.ncnn.bin");
        }

        JSONObject post = root.optJSONObject("postprocess");
        if (post != null) {
            spec.postType = post.optString("type", spec.isDetect() ? POST_YOLO_ANCHOR : POST_SOFTMAX_TOPK);
            spec.applySoftmax = post.optBoolean("applySoftmax", false);
            spec.probThreshold = (float) post.optDouble("probThreshold", 0.25);
            spec.nmsThreshold = (float) post.optDouble("nmsThreshold", 0.45);
            spec.topk = post.optInt("topk", 5);
        }
        return spec;
    }

    private static float[] readTriple(JSONArray array, float fallback) throws Exception {
        float[] values = {fallback, fallback, fallback};
        if (array == null) {
            return values;
        }
        if (array.length() != 3) {
            throw new IllegalArgumentException("mean/std 必须是 3 个数值");
        }
        for (int i = 0; i < 3; i++) {
            values[i] = (float) array.getDouble(i);
        }
        return values;
    }

    /** 导入时校验 manifest 的合法性；labelsCount 为标签文件实际行数。 */
    public void validate(String expectedTask, int labelsCount) throws Exception {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("不支持的模型包版本：" + schemaVersion);
        }
        if (!expectedTask.equals(task)) {
            throw new IllegalArgumentException("模型任务类型为 " + task + "，与当前导入入口不一致");
        }
        if (inputW < 64 || inputH < 64 || inputW > 2048 || inputH > 2048) {
            throw new IllegalArgumentException("输入尺寸超出支持范围：" + inputW + "x" + inputH);
        }
        if (numClasses <= 0 || numClasses > 10000) {
            throw new IllegalArgumentException("类别数非法：" + numClasses);
        }
        if (labelsCount != numClasses) {
            throw new IllegalArgumentException("标签行数 " + labelsCount + " 与类别数 " + numClasses + " 不一致");
        }
        if (scale <= 0f) {
            throw new IllegalArgumentException("input.scale 必须大于 0");
        }
        for (int i = 0; i < 3; i++) {
            if (std[i] <= 0f) {
                throw new IllegalArgumentException("input.std 必须大于 0");
            }
        }
        if (padValue < 0f || padValue > 255f) {
            throw new IllegalArgumentException("input.padValue 超出 0~255");
        }
        boolean supportedPost = isDetect()
                ? POST_YOLO_ANCHOR.equals(postType)
                : POST_SOFTMAX_TOPK.equals(postType);
        if (!supportedPost) {
            throw new IllegalArgumentException("不支持的 postprocess.type：" + postType);
        }
        if (isDetect() && (inputW % 32 != 0 || inputH % 32 != 0)) {
            throw new IllegalArgumentException("检测模型输入尺寸需为 32 的倍数");
        }
        if (!isSafePath(labelsFile) || !isSafePath(paramFile) || !isSafePath(binFile)) {
            throw new IllegalArgumentException("模型包内文件路径不合法");
        }
    }

    private static boolean isSafePath(String path) {
        return path != null && !path.isEmpty() && !path.startsWith("/")
                && !path.contains("..") && !path.contains("\\");
    }

    public int[] nativeInts() {
        if (isDetect()) {
            return new int[]{numClasses, inputW, inputH};
        }
        return new int[]{numClasses, inputW};
    }

    public float[] nativeFloats() {
        if (isDetect()) {
            return new float[]{scale,
                    mean[0], mean[1], mean[2],
                    std[0], std[1], std[2],
                    padValue, probThreshold, nmsThreshold};
        }
        return new float[]{scale,
                mean[0], mean[1], mean[2],
                std[0], std[1], std[2],
                applySoftmax ? 1f : 0f};
    }
}
