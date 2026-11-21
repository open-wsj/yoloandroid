package com.example.yolo11ncnn;

import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * YOLO11 ncnn 推理封装。
 */
public class Yolo11Detector implements AutoCloseable {

    static {
        System.loadLibrary("yolo11ncnn");
    }

    /** 每个结果 6 个 float：x1, y1, x2, y2, score, classId */
    public static final int RESULT_STRIDE = 6;

    private long handle;
    private final String[] labels;

    public Yolo11Detector(AssetManager assets, String paramName, String binName, int numThreads) {
        handle = nativeCreate(assets, paramName, binName, numThreads);
        if (handle == 0) {
            throw new IllegalStateException("模型加载失败，请检查 assets/" + paramName + " 与 " + binName);
        }
        labels = loadLabels(assets);
    }

    public String[] labels() {
        return labels;
    }

    public String labelOf(int classId) {
        if (classId >= 0 && classId < labels.length) {
            return labels[classId];
        }
        return String.valueOf(classId);
    }

    /**
     * @param rgba 宽 * 高 * 4 的 RGBA 像素
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

    private static String[] loadLabels(AssetManager assets) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(assets.open("labels.txt"), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        } catch (IOException e) {
            return new String[0];
        }
    }

    private native long nativeCreate(AssetManager assets, String paramName, String binName,
                                     int numThreads);

    private native void nativeDestroy(long handle);

    private native float[] nativeDetect(long handle, byte[] rgba, int width, int height,
                                        float probThreshold, float nmsThreshold);
}
