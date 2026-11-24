package com.example.yolo11ncnn;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 模型库：导入 .yolozip、读取 manifest、切换/回退模型。
 *
 * 模型包结构（zip）：
 *   manifest.json
 *   model.ncnn.param
 *   model.ncnn.bin
 *   labels.txt
 */
public final class ModelManager {

    public static final String KEY_ACTIVE_DETECT = "model_id_detect";
    public static final String KEY_ACTIVE_CLASSIFY = "model_id_classify";

    private static final long MAX_ENTRY_BYTES = 300L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 400L * 1024 * 1024;
    private static final int MAX_ENTRIES = 32;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private ModelManager() {
    }

    public static final class ImportedModel {
        public final ModelSpec spec;
        public final File dir;

        ImportedModel(ModelSpec spec, File dir) {
            this.spec = spec;
            this.dir = dir;
        }
    }

    public static File modelsRoot(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File root = new File(base, "models");
        if (!root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        return root;
    }

    private static String prefKey(String task) {
        return ModelSpec.TASK_CLASSIFY.equals(task) ? KEY_ACTIVE_CLASSIFY : KEY_ACTIVE_DETECT;
    }

    /** 返回当前激活的模型目录名；空字符串表示使用内置模型。 */
    public static String activeKey(Context context, String task) {
        return AppPrefs.get(context).getString(prefKey(task), "");
    }

    /** 返回当前激活的导入模型目录；内置模型返回 null。 */
    public static File activeDir(Context context, String task) {
        String key = activeKey(context, task);
        if (key.isEmpty()) {
            return null;
        }
        File dir = new File(modelsRoot(context), key);
        return dir.isDirectory() ? dir : null;
    }

    /** 读取目录下的 manifest.json。 */
    public static ModelSpec loadSpec(File dir) throws Exception {
        File manifest = new File(dir, "manifest.json");
        if (!manifest.isFile()) {
            throw new IllegalStateException("模型目录缺少 manifest.json");
        }
        return ModelSpec.fromJson(readText(manifest, MAX_MANIFEST_BYTES));
    }

    /** UI 展示用：当前激活模型（导入或内置）。 */
    public static ModelSpec activeSpec(Context context, String task) {
        try {
            File dir = activeDir(context, task);
            if (dir != null) {
                return loadSpec(dir);
            }
        } catch (Exception ignored) {
            // 回退内置
        }
        return ModelSpec.TASK_DETECT.equals(task) ? ModelSpec.builtinDetect() : ModelSpec.builtinClassify();
    }

    /** 恢复内置模型（同时清除两种任务的选择）。 */
    public static void resetToBuiltin(Context context) {
        AppPrefs.get(context).edit()
                .remove(KEY_ACTIVE_DETECT)
                .remove(KEY_ACTIVE_CLASSIFY)
                .apply();
    }

    /**
     * 导入模型包。会完成：解压 → manifest 校验 → 标签/param 校验 → 原子落盘 → 设为当前模型。
     */
    public static ImportedModel importPackage(Context context, Uri uri, String expectedTask) throws Exception {
        File root = modelsRoot(context);
        File tempDir = new File(root, ".import-" + System.currentTimeMillis());
        deleteRecursively(tempDir);
        if (!tempDir.mkdirs()) {
            throw new IOException("无法创建临时目录");
        }

        try {
            extractZip(context, uri, tempDir);
            ModelSpec spec = loadSpec(tempDir);
            File labels = new File(tempDir, spec.labelsFile);
            int labelsCount = countLabels(labels);
            spec.validate(expectedTask, labelsCount);

            File param = new File(tempDir, spec.paramFile);
            File bin = new File(tempDir, spec.binFile);
            validateParam(param);
            if (!bin.isFile() || bin.length() < 1024) {
                throw new IllegalArgumentException("模型权重文件缺失或过小");
            }

            File target = new File(root, buildDirName(expectedTask, spec));
            deleteRecursively(target);
            if (!tempDir.renameTo(target)) {
                copyRecursively(tempDir, target);
                deleteRecursively(tempDir);
            }

            AppPrefs.get(context).edit()
                    .putString(prefKey(expectedTask), target.getName())
                    .apply();
            return new ImportedModel(spec, target);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void extractZip(Context context, Uri uri, File destDir) throws Exception {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new IOException("无法读取所选文件");
        }
        String canonicalRoot = destDir.getCanonicalPath();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("模型包文件数量过多");
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    throw new IllegalArgumentException("模型包含非法路径：" + name);
                }
                File outFile = new File(destDir, name);
                if (!outFile.getCanonicalPath().startsWith(canonicalRoot + File.separator)) {
                    throw new IllegalArgumentException("模型包含非法路径：" + name);
                }
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录：" + parent.getName());
                }
                long entryBytes = 0;
                try (OutputStream output = new FileOutputStream(outFile)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        entryBytes += read;
                        total += read;
                        if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                            throw new IllegalArgumentException("模型包解压后体积超限");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
        if (entries == 0) {
            throw new IllegalArgumentException("模型包为空或不是有效的 zip");
        }
    }

    private static void validateParam(File param) throws Exception {
        if (!param.isFile()) {
            throw new IllegalArgumentException("缺少模型结构文件：" + param.getName());
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(param), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (first == null || !first.trim().equals("7767517")) {
                throw new IllegalArgumentException("param 文件格式不正确（缺少 ncnn 魔数）");
            }
        }
    }

    private static int countLabels(File labels) throws Exception {
        if (!labels.isFile()) {
            throw new IllegalArgumentException("缺少标签文件：" + labels.getName());
        }
        int count = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(labels), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String buildDirName(String task, ModelSpec spec) {
        StringBuilder builder = new StringBuilder(task).append('_');
        String id = spec.id == null ? "model" : spec.id;
        for (int i = 0; i < id.length() && builder.length() < 48; i++) {
            char c = id.charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return builder.toString();
    }

    private static String readText(File file, int maxBytes) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("manifest.json 过大");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void copyRecursively(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("无法创建目录：" + dst);
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(dst, child.getName()));
                }
            }
        } else {
            try (InputStream input = new FileInputStream(src);
                 OutputStream output = new FileOutputStream(dst)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            }
        }
    }

    /** 供设置页展示用：模型包摘要。 */
    public static String describe(Context context, String task) {
        File dir = activeDir(context, task);
        if (dir == null) {
            return ModelSpec.TASK_DETECT.equals(task) ? "内置 YOLO26m" : "内置 YOLO26m-cls";
        }
        try {
            ModelSpec spec = loadSpec(dir);
            return spec.name + " " + spec.version + "（" + spec.numClasses + " 类）";
        } catch (Exception e) {
            return "导入模型（manifest 读取失败）";
        }
    }
}
