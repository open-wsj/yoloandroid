package com.example.yolo11ncnn;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * YOLO 检测模型封装：支持内置 assets 模型与导入模型。
 */
public class Yolo11Detector implements AutoCloseable {

    static {
        System.loadLibrary("yolo11ncnn");
    }

    /** 每个结果 6 个 float：x1, y1, x2, y2, score, classId */
    public static final int RESULT_STRIDE = 6;

    private long handle;
    private final String[] labels;
    private final ModelSpec spec;

    /** 内置模型：从 APK assets 加载。 */
    public Yolo11Detector(AssetManager assets, ModelSpec spec, int numThreads) {
        this.spec = spec;
        handle = nativeCreate(assets, spec.paramFile, spec.binFile,
                spec.nativeInts(), spec.nativeFloats(), numThreads);
        if (handle == 0) {
            throw new IllegalStateException("检测模型加载失败：" + spec.paramFile);
        }
        labels = loadLabels(assets, spec.labelsFile);
        if (labels.length != spec.numClasses) {
            close();
            throw new IllegalStateException("检测标签数量与类别数不一致");
        }
    }

    /** 导入模型：从模型目录加载。 */
    public Yolo11Detector(File modelDir, ModelSpec spec, int numThreads) {
        this.spec = spec;
        handle = nativeCreateFromFile(
                new File(modelDir, spec.paramFile).getAbsolutePath(),
                new File(modelDir, spec.binFile).getAbsolutePath(),
                spec.nativeInts(), spec.nativeFloats(), numThreads);
        if (handle == 0) {
            throw new IllegalStateException("检测模型加载失败：" + spec.name);
        }
        labels = loadLabels(new File(modelDir, spec.labelsFile));
        if (labels.length != spec.numClasses) {
            close();
            throw new IllegalStateException("检测标签数量与类别数不一致");
        }
    }

    public String[] labels() {
        return labels;
    }

    public ModelSpec spec() {
        return spec;
    }

    public String labelOf(int classId) {
        if (classId >= 0 && classId < labels.length) {
            return labels[classId];
        }
        return String.valueOf(classId);
    }

    /**
     * @param rgba 宽 * 高 * 4 的 RGBA 像素
     * @param probThreshold 小于 0 时使用 manifest 中的默认值
     * @param nmsThreshold  小于 0 时使用 manifest 中的默认值
     * @return 检测结果，每 6 个 float 一个框
     */
    public float[] detect(byte[] rgba, int width, int height,
                          float probThreshold, float nmsThreshold) {
        if (handle == 0) {
            return new float[0];
        }
        float[] result = nativeDetect(handle, rgba, width, height, probThreshold, nmsThreshold);
        return result == null ? new float[0] : result;
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static String[] loadLabels(AssetManager assets, String name) {
        try (InputStream input = assets.open(name)) {
            return readLines(input);
        } catch (IOException e) {
            return new String[0];
        }
    }

    private static String[] loadLabels(File file) {
        try (InputStream input = new FileInputStream(file)) {
            return readLines(input);
        } catch (IOException e) {
            return new String[0];
        }
    }

    private static String[] readLines(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        }
    }

    private native long nativeCreate(AssetManager assets, String paramName, String binName,
                                     int[] ints, float[] floats, int numThreads);

    private native long nativeCreateFromFile(String paramPath, String binPath,
                                             int[] ints, float[] floats, int numThreads);

    private native void nativeDestroy(long handle);

    private native float[] nativeDetect(long handle, byte[] rgba, int width, int height,
                                        float probThreshold, float nmsThreshold);
}
