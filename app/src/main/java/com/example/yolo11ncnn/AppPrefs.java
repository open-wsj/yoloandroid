package com.example.yolo11ncnn;

import android.content.Context;
import android.content.SharedPreferences;

/** 全局设置项（模式 / 摄像头）。 */
public final class AppPrefs {

    public static final String NAME = "app_prefs";
    public static final String KEY_MODE = "mode";
    public static final String KEY_CAMERA_ID = "camera_id";

    public static final String MODE_DETECT = "detect";
    public static final String MODE_CLASSIFY = "classify";

    private AppPrefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String mode(Context context) {
        return get(context).getString(KEY_MODE, MODE_DETECT);
    }

    public static String cameraId(Context context) {
        return get(context).getString(KEY_CAMERA_ID, "");
    }
}
