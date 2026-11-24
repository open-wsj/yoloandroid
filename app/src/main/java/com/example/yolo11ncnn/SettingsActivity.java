package com.example.yolo11ncnn;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 设置页：工作模式 + 摄像头选择 + 模型管理。 */
public class SettingsActivity extends ComponentActivity {

    private final List<String> cameraIds = new ArrayList<>();
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor();
    private boolean binding;
    private String pendingTask;

    private final ActivityResultLauncher<String[]> pickModel =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                String task = pendingTask;
                pendingTask = null;
                if (uri != null && task != null) {
                    importModel(uri, task);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applyInsets();

        RadioButton modeDetect = findViewById(R.id.mode_detect);
        RadioButton modeClassify = findViewById(R.id.mode_classify);
        Spinner cameraSpinner = findViewById(R.id.camera_spinner);

        populateCameras(cameraSpinner);

        binding = true;
        if (AppPrefs.MODE_CLASSIFY.equals(AppPrefs.mode(this))) {
            modeClassify.setChecked(true);
        } else {
            modeDetect.setChecked(true);
        }
        binding = false;

        modeDetect.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                AppPrefs.get(this).edit().putString(AppPrefs.KEY_MODE, AppPrefs.MODE_DETECT).apply();
            }
        });
        modeClassify.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                AppPrefs.get(this).edit().putString(AppPrefs.KEY_MODE, AppPrefs.MODE_CLASSIFY).apply();
            }
        });

        findViewById(R.id.settings_done).setOnClickListener(v -> finish());

        findViewById(R.id.btn_import_detect).setOnClickListener(v -> {
            pendingTask = ModelSpec.TASK_DETECT;
            pickModel.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
        });
        findViewById(R.id.btn_import_classify).setOnClickListener(v -> {
            pendingTask = ModelSpec.TASK_CLASSIFY;
            pickModel.launch(new String[]{"application/zip", "application/octet-stream", "*/*"});
        });
        findViewById(R.id.btn_reset_models).setOnClickListener(v -> {
            ModelManager.resetToBuiltin(this);
            refreshModelInfo();
            Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show();
        });
        refreshModelInfo();

        TextView versionText = findViewById(R.id.settings_version);
        versionText.setText(String.format(java.util.Locale.US,
                "当前版本 %s (versionCode %d)",
                UpdateManager.currentVersionName(this),
                UpdateManager.currentVersionCode(this)));

        findViewById(R.id.settings_check_update).setOnClickListener(v -> {
            Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
            UpdateManager.check(this, true, (info, error) -> {
                if (error != null) {
                    Toast.makeText(this, "检查失败：" + error, Toast.LENGTH_LONG).show();
                } else if (!UpdateManager.hasUpdate(this, info)) {
                    Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show();
                } else {
                    UpdateManager.showUpdateDialog(this, info);
                }
            });
        });
    }

    private void populateCameras(Spinner spinner) {
        List<String> labels = new ArrayList<>();
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                String facingText = "外接";
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        facingText = "后置";
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        facingText = "前置";
                    }
                }
                cameraIds.add(id);
                labels.add("摄像头 " + id + " · " + facingText);
            }
        } catch (Exception ignored) {
            // 枚举失败时退回默认摄像头
        }

        if (cameraIds.isEmpty()) {
            cameraIds.add("");
            labels.add("默认摄像头（后置）");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String saved = AppPrefs.cameraId(this);
        int index = Math.max(0, cameraIds.indexOf(saved));
        spinner.setSelection(index);

        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (binding) {
                    return;
                }
                AppPrefs.get(SettingsActivity.this).edit()
                        .putString(AppPrefs.KEY_CAMERA_ID, cameraIds.get(position))
                        .apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private void importModel(Uri uri, String task) {
        Toast.makeText(this, R.string.settings_importing, Toast.LENGTH_SHORT).show();
        importExecutor.execute(() -> {
            try {
                ModelManager.ImportedModel imported = ModelManager.importPackage(this, uri, task);
                runOnUiThread(() -> {
                    refreshModelInfo();
                    Toast.makeText(this,
                            getString(R.string.settings_import_ok, imported.spec.name),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.settings_import_fail, message),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void refreshModelInfo() {
        TextView info = findViewById(R.id.model_info);
        info.setText(getString(R.string.settings_model_info,
                ModelManager.describe(this, ModelSpec.TASK_DETECT),
                ModelManager.describe(this, ModelSpec.TASK_CLASSIFY)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        importExecutor.shutdown();
    }

    private void applyInsets() {
        android.view.View root = findViewById(R.id.settings_root);
        final int baseTop = root.getPaddingTop();
        final int baseBottom = root.getPaddingBottom();
        final int baseLeft = root.getPaddingLeft();
        final int baseRight = root.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(baseLeft + bars.left, baseTop + bars.top,
                    baseRight + bars.right, baseBottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
