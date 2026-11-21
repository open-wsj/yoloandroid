package com.example.yolo11ncnn;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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

/**
 * 应用更新：检查 update.json → 弹窗 → DownloadManager 下载 → SHA-256 校验 → 系统安装器。
 */
public final class UpdateManager {

    /** update.json 地址，随仓库 main 分支更新。 */
    public static final String UPDATE_URL =
            "https://gitee.com/open-zynq/yoloandroid/raw/main/update.json";

    private static final String KEY_LAST_CHECK = "last_update_check";
    private static final String KEY_DOWNLOAD_ID = "update_download_id";
    private static final String KEY_DOWNLOAD_SHA = "update_download_sha";
    private static final String KEY_DOWNLOAD_NAME = "update_download_name";
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
            } catch (Exception e) {
                error = e.getMessage();
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
                .setPositiveButton("立即更新", (dialog, which) -> {
                    try {
                        startDownload(activity, info);
                        Toast.makeText(activity, "开始下载更新包…", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(activity, "下载失败：" + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
        if (mandatory) {
            builder.setNegativeButton("退出应用", (dialog, which) -> activity.finishAffinity());
        } else {
            builder.setNegativeButton("稍后再说", null);
        }
        builder.show();
    }

    public static long startDownload(Context context, UpdateInfo info) {
        File dir = new File(context.getExternalFilesDir(null), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录");
        }
        String fileName = "YOLO11-" + info.latestVersionName + ".apk";
        File apkFile = new File(dir, fileName);
        if (apkFile.exists() && !apkFile.delete()) {
            // 忽略删除失败，DownloadManager 会覆盖目标文件
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("YOLO11 更新 " + info.latestVersionName)
                .setDescription("下载完成后点击安装")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, null, "updates/" + fileName);

        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("系统下载服务不可用");
        }
        long id = manager.enqueue(request);
        AppPrefs.get(context).edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_DOWNLOAD_SHA, info.sha256)
                .putString(KEY_DOWNLOAD_NAME, fileName)
                .apply();
        return id;
    }

    /**
     * 查询下载状态；成功则校验 SHA-256 并拉起安装器。
     *
     * @param downloadId 传 -1 表示读取本地保存的下载 id（用于进程被杀后恢复）
     */
    public static void handleDownload(Context context, long downloadId) {
        SharedPreferences prefs = AppPrefs.get(context);
        long id = downloadId > 0 ? downloadId : prefs.getLong(KEY_DOWNLOAD_ID, -1);
        if (id <= 0) {
            return;
        }
        DownloadManager manager =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return;
        }

        try (Cursor cursor = manager.query(
                new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                return;
            }
            int status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_FAILED) {
                prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_SHA).apply();
                return;
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                return;
            }

            Uri uri = manager.getUriForDownloadedFile(id);
            if (uri == null) {
                return;
            }
            String expectedSha = prefs.getString(KEY_DOWNLOAD_SHA, "");
            String fileName = prefs.getString(KEY_DOWNLOAD_NAME, "update.apk");

            new Thread(() -> {
                String error = null;
                File target = new File(new File(context.getExternalFilesDir(null), "updates"),
                        fileName);
                try {
                    String actualSha = copyAndHash(context, uri, target);
                    if (!expectedSha.isEmpty()
                            && !expectedSha.equalsIgnoreCase(actualSha)) {
                        error = "安装包校验失败（SHA-256 不匹配）";
                    }
                } catch (Exception e) {
                    error = "安装包处理失败：" + e.getMessage();
                }

                String failure = error;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (failure != null) {
                        prefs.edit().remove(KEY_DOWNLOAD_ID)
                                .remove(KEY_DOWNLOAD_SHA).apply();
                        Toast.makeText(context, failure, Toast.LENGTH_LONG).show();
                    } else {
                        prefs.edit().remove(KEY_DOWNLOAD_ID)
                                .remove(KEY_DOWNLOAD_SHA).apply();
                        installApk(context, target);
                    }
                });
            }, "update-install").start();
        }
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

    private static String copyAndHash(Context context, Uri uri, File target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建目录");
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IllegalStateException("无法读取下载文件");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }
}
