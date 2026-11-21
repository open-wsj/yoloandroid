package com.example.yolo11ncnn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用更新：检查 update.json → 弹窗 → 应用内下载 → SHA-256 校验 → 系统安装器。
 */
public final class UpdateManager {

    /** update.json 地址，随仓库 main 分支更新。 */
    public static final String UPDATE_URL =
            "https://gitee.com/open-zynq/yoloandroid/raw/main/update.json";

    private static final String KEY_LAST_CHECK = "last_update_check";
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;

    private UpdateManager() {
    }

    /** 更新信息。 */
    public static class UpdateInfo {
        public int latestVersionCode;
        public int minSupportedVersionCode;
        public String latestVersionName = "";
        public String apkUrl = "";
        public String sha256 = "";
        public String changelog = "";
        public long sizeBytes;
    }

    /** 检查回调，在主线程触发；无更新时 info 为 null，出错时 error 非空。 */
    public interface CheckCallback {
        void onResult(UpdateInfo info, String error);
    }

    private interface DownloadCallback {
        void onProgress(int percent);

        void onFinished(File apk, String error);
    }

    public static int currentVersionCode(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * @param manual 手动检查时忽略节流
     */
    public static void check(Context context, boolean manual, CheckCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        SharedPreferences prefs = AppPrefs.get(context);
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        if (!manual && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
            mainHandler.post(() -> callback.onResult(null, null));
            return;
        }

        new Thread(() -> {
            UpdateInfo info = null;
            String error = null;
            try {
                HttpURLConnection connection =
                        (HttpURLConnection) new URL(UPDATE_URL).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", "YOLO11-Updater");
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + code);
                }

                StringBuilder builder = new StringBuilder();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }

                JSONObject json = new JSONObject(builder.toString());
                info = new UpdateInfo();
                info.latestVersionCode = json.optInt("latestVersionCode", 0);
                info.minSupportedVersionCode = json.optInt("minSupportedVersionCode", 0);
                info.latestVersionName = json.optString("latestVersionName", "");
                info.apkUrl = json.optString("apkUrl", "");
                info.sha256 = json.optString("sha256", "");
                info.changelog = json.optString("changelog", "");
                info.sizeBytes = json.optLong("sizeBytes", 0);

                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                Log.i("YOLO11-Update", "check ok, latest=" + info.latestVersionName);
            } catch (Exception e) {
                error = e.getMessage();
                Log.w("YOLO11-Update", "check failed: " + e);
            }

            UpdateInfo result = info;
            String failure = error;
            mainHandler.post(() -> callback.onResult(result, failure));
        }, "update-check").start();
    }

    public static boolean hasUpdate(Context context, UpdateInfo info) {
        return info != null && info.latestVersionCode > currentVersionCode(context);
    }

    public static void showUpdateDialog(Activity activity, UpdateInfo info) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        boolean mandatory = currentVersionCode(activity) < info.minSupportedVersionCode;
        String sizeText = info.sizeBytes > 0
                ? String.format(Locale.US, "%.1f MB", info.sizeBytes / 1048576f)
                : "未知大小";
        String message = info.changelog
                + "\n\n当前版本：" + currentVersionName(activity)
                + "  →  " + info.latestVersionName
                + "\n安装包大小：" + sizeText;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + info.latestVersionName)
                .setMessage(message)
                .setCancelable(!mandatory)
                .setPositiveButton("立即更新", (dialog, which) ->
                        startDownloadWithProgress(activity, info));
        if (mandatory) {
            builder.setNegativeButton("退出应用", (dialog, which) -> activity.finishAffinity());
        } else {
            builder.setNegativeButton("稍后再说", null);
        }
        builder.show();
    }

    private static void startDownloadWithProgress(Activity activity, UpdateInfo info) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int padding = (int) (20 * density);

        ProgressBar progressBar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        TextView label = new TextView(activity);
        label.setTextColor(Color.WHITE);
        label.setText("准备下载…");

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, 0);
        layout.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = padding / 2;
        layout.addView(progressBar, barParams);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("正在下载 " + info.latestVersionName)
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton("取消", (d, which) -> cancelled.set(true))
                .show();

        download(activity, info, cancelled, new DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                progressBar.setProgress(percent);
                label.setText(String.format(Locale.US, "已下载 %d%%", percent));
            }

            @Override
            public void onFinished(File apk, String error) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    dialog.dismiss();
                }
                if (error != null) {
                    Toast.makeText(activity, "更新失败：" + error, Toast.LENGTH_LONG).show();
                } else if (apk != null) {
                    installApk(activity, apk);
                }
            }
        });
    }

    private static void download(Context context, UpdateInfo info, AtomicBoolean cancelled,
                                 DownloadCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File target = null;
            try {
                File dir = new File(context.getExternalFilesDir(null), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建下载目录");
                }
                String fileName = "YOLO11-" + info.latestVersionName + ".apk";
                target = new File(dir, fileName);
                if (target.exists() && !target.delete()) {
                    // 目标文件无法删除时直接覆盖写入
                }

                HttpURLConnection connection =
                        (HttpURLConnection) new URL(info.apkUrl).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent", "YOLO11-Updater");
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + code);
                }

                long total = connection.getContentLengthLong();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                int lastPercent = -1;
                long downloaded = 0;

                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        if (cancelled.get()) {
                            throw new IllegalStateException("已取消下载");
                        }
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int percent = (int) (downloaded * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                int shown = percent;
                                mainHandler.post(() -> callback.onProgress(shown));
                            }
                        }
                    }
                }

                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) {
                    hex.append(String.format(Locale.US, "%02x", b));
                }
                if (!info.sha256.isEmpty()
                        && !info.sha256.equalsIgnoreCase(hex.toString())) {
                    throw new IllegalStateException("安装包校验失败（SHA-256 不匹配）");
                }

                File result = target;
                mainHandler.post(() -> callback.onFinished(result, null));
            } catch (Exception e) {
                if (target != null && target.exists() && !target.delete()) {
                    Log.w("YOLO11-Update", "failed to delete partial file");
                }
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.w("YOLO11-Update", "download failed: " + message);
                mainHandler.post(() -> callback.onFinished(null, message));
            }
        }, "update-download").start();
    }

    public static void installApk(Context context, File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !context.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(context, "请允许「安装未知应用」后重试", Toast.LENGTH_LONG).show();
            try {
                context.startActivity(new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // 部分 ROM 无此页面，只能提示用户手动开启
            }
            return;
        }

        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }
}
