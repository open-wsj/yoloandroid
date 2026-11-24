package com.example.yolo11ncnn;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {

    private static final int EMBEDDED_THREADS = 4;
    private static final int VIDEO_FRAME_STEP_MS = 300;

    private PreviewView previewView;
    private ImageView mediaView;
    private OverlayView overlayView;
    private TextView statusText;
    private TextView clsResult;
    private View detectBar;
    private View clsBar;
    private Button btnPickVideo;
    private Button btnPauseVideo;
    private Button btnStopVideo;
    private Button btnPickImage;
    private Button btnShutter;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Object modelLock = new Object();

    private Yolo11Detector detector;
    private Classifier classifier;
    private volatile boolean detectorReady = false;
    private volatile boolean classifierReady = false;

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;

    private String appliedMode;
    private String appliedCameraId;
    private String appliedDetectKey = "";
    private String appliedClassifyKey = "";
    private String loadedDetectKey = "";
    private String loadedClassifyKey = "";
    private float smoothedFps = 0f;

    private volatile boolean videoActive = false;
    private volatile boolean videoPaused = false;
    private volatile Thread videoThread;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickImage =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    onImagePicked(uri);
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> pickVideo =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    startVideoPlayback(uri);
                }
            });

    private final ActivityResultLauncher<String> requestCamera =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    appliedMode = null;
                    applyMode();
                } else {
                    statusText.setText(R.string.camera_permission_denied);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview_view);
        mediaView = findViewById(R.id.media_view);
        overlayView = findViewById(R.id.overlay_view);
        statusText = findViewById(R.id.status_text);
        clsResult = findViewById(R.id.cls_result);
        detectBar = findViewById(R.id.detect_bar);
        clsBar = findViewById(R.id.cls_bar);
        btnPickVideo = findViewById(R.id.btn_pick_video);
        btnPauseVideo = findViewById(R.id.btn_pause_video);
        btnStopVideo = findViewById(R.id.btn_stop_video);
        btnPickImage = findViewById(R.id.btn_pick_image);
        btnShutter = findViewById(R.id.btn_shutter);

        applyInsets();
        trackHudBoundsForOverlay();
        checkForUpdatesSilently();

        findViewById(R.id.settings_button).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnPickVideo.setOnClickListener(v -> pickVideo.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                        .build()));
        btnPauseVideo.setOnClickListener(v -> toggleVideoPause());
        btnStopVideo.setOnClickListener(v -> stopVideoPlayback(true));
        btnPickImage.setOnClickListener(v -> pickImage.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
        btnShutter.setOnClickListener(v -> takePhoto());

        if (hasCameraPermission()) {
            applyMode();
        } else {
            requestCamera.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()) {
            applyMode();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Thread thread = videoThread;
        videoActive = false;
        videoPaused = false;
        videoThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        analysisExecutor.shutdown();
        try {
            analysisExecutor.awaitTermination(800, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        ioExecutor.shutdown();
        synchronized (modelLock) {
            if (detector != null) {
                detector.close();
                detector = null;
            }
            detectorReady = false;
            if (classifier != null) {
                classifier.close();
                classifier = null;
            }
            classifierReady = false;
        }
    }

    // ---------------- 应用更新 ----------------

    private void checkForUpdatesSilently() {
        UpdateManager.check(this, false, (info, error) -> {
            if (error == null && UpdateManager.hasUpdate(this, info)) {
                UpdateManager.showUpdateDialog(this, info);
            }
        });
    }

    // ---------------- 模式与相机 ----------------

    private void applyMode() {
        String mode = AppPrefs.mode(this);
        String cameraId = AppPrefs.cameraId(this);
        String detectKey = ModelManager.activeKey(this, ModelSpec.TASK_DETECT);
        String classifyKey = ModelManager.activeKey(this, ModelSpec.TASK_CLASSIFY);

        boolean modeChanged = !mode.equals(appliedMode) || !cameraId.equals(appliedCameraId);
        boolean modelChanged = !detectKey.equals(appliedDetectKey)
                || !classifyKey.equals(appliedClassifyKey);
        if (!modeChanged && !modelChanged) {
            return;
        }
        appliedMode = mode;
        appliedCameraId = cameraId;
        appliedDetectKey = detectKey;
        appliedClassifyKey = classifyKey;

        stopVideoPlayback(true);

        boolean detect = AppPrefs.MODE_DETECT.equals(mode);
        detectBar.setVisibility(detect ? View.VISIBLE : View.GONE);
        clsBar.setVisibility(detect ? View.GONE : View.VISIBLE);
        overlayView.setVisibility(detect ? View.VISIBLE : View.GONE);
        overlayView.clear();
        clsResult.setVisibility(View.GONE);
        mediaView.setImageDrawable(null);
        mediaView.setVisibility(View.GONE);

        ensureModel(detect);

        if (hasCameraPermission() && modeChanged) {
            startCamera(detect);
        }
        statusText.setText(detect ? "检测  CPU×" + EMBEDDED_THREADS : "分类  CPU×" + EMBEDDED_THREADS);
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureModel(boolean detect) {
        String key = ModelManager.activeKey(this,
                detect ? ModelSpec.TASK_DETECT : ModelSpec.TASK_CLASSIFY);
        if (detect) {
            if (detector != null && key.equals(loadedDetectKey)) {
                return;
            }
            detectorReady = false;
            statusText.setText("检测模型加载中…");
            // 与 analyze 串行，避免关闭仍在推理中的模型
            analysisExecutor.execute(() -> {
                // 等待在途视频检测结束，避免关闭正在使用的模型
                Thread thread = videoThread;
                if (thread != null) {
                    try {
                        thread.join(800);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                synchronized (modelLock) {
                    if (detector != null) {
                        detector.close();
                        detector = null;
                    }
                }
                try {
                    File dir = ModelManager.activeDir(MainActivity.this, ModelSpec.TASK_DETECT);
                    Yolo11Detector loaded = dir == null
                            ? new Yolo11Detector(getAssets(), ModelSpec.builtinDetect(), EMBEDDED_THREADS)
                            : new Yolo11Detector(dir, ModelManager.loadSpec(dir), EMBEDDED_THREADS);
                    synchronized (modelLock) {
                        detector = loaded;
                    }
                    loadedDetectKey = key;
                    detectorReady = true;
                    runOnUiThread(() -> {
                        synchronized (modelLock) {
                            if (detector != null) {
                                overlayView.setLabels(detector.labels());
                            }
                        }
                        if (isDetectMode()) {
                            statusText.setText("检测  CPU×" + EMBEDDED_THREADS);
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> statusText.setText("检测模型加载失败: " + t.getMessage()));
                }
            });
        } else {
            if (classifier != null && key.equals(loadedClassifyKey)) {
                return;
            }
            classifierReady = false;
            statusText.setText("分类模型加载中…");
            ioExecutor.execute(() -> {
                synchronized (modelLock) {
                    if (classifier != null) {
                        classifier.close();
                        classifier = null;
                    }
                }
                try {
                    File dir = ModelManager.activeDir(MainActivity.this, ModelSpec.TASK_CLASSIFY);
                    Classifier loaded = dir == null
                            ? new Classifier(getAssets(), ModelSpec.builtinClassify(), EMBEDDED_THREADS)
                            : new Classifier(dir, ModelManager.loadSpec(dir), EMBEDDED_THREADS);
                    synchronized (modelLock) {
                        classifier = loaded;
                    }
                    loadedClassifyKey = key;
                    classifierReady = true;
                    runOnUiThread(() -> {
                        if (!isDetectMode()) {
                            statusText.setText("分类  CPU×" + EMBEDDED_THREADS + "  ·  相册 / 拍照");
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> statusText.setText("分类模型加载失败: " + t.getMessage()));
                }
            });
        }
    }

    private boolean isDetectMode() {
        return AppPrefs.MODE_DETECT.equals(appliedMode);
    }

    private void startCamera(boolean detect) {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases(detect);
            } catch (Exception e) {
                statusText.setText("相机启动失败: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases(boolean detect) {
        if (cameraProvider == null) {
            return;
        }
        cameraProvider.unbindAll();
        CameraSelector selector = buildCameraSelector(appliedCameraId);
        try {
            bindInternal(selector, detect);
        } catch (Exception firstError) {
            // 指定摄像头不可用时回退到默认后置
            try {
                bindInternal(CameraSelector.DEFAULT_BACK_CAMERA, detect);
                statusText.setText("指定摄像头不可用，已回退默认后置");
            } catch (Exception secondError) {
                statusText.setText("相机启动失败: " + secondError.getMessage());
            }
        }
    }

    private void bindInternal(CameraSelector selector, boolean detect) {
        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        if (detect) {
            ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(640, 480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setOutputImageRotationEnabled(true)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            analysis.setAnalyzer(analysisExecutor, this::analyze);
            cameraProvider.bindToLifecycle(this, selector, preview, analysis);
        } else {
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();
            cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
        }
    }

    private CameraSelector buildCameraSelector(String cameraId) {
        if (cameraId == null || cameraId.isEmpty()) {
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }
        return new CameraSelector.Builder().addCameraFilter(cameraInfos -> {
            List<CameraInfo> filtered = new ArrayList<>();
            for (CameraInfo info : cameraInfos) {
                try {
                    if (cameraId.equals(Camera2CameraInfo.from(info).getCameraId())) {
                        filtered.add(info);
                    }
                } catch (Exception ignored) {
                    // 忽略无法读取 id 的摄像头
                }
            }
            return filtered;
        }).build();
    }

    // ---------------- 实时检测 ----------------

    private void analyze(ImageProxy image) {
        try {
            if (videoActive || !detectorReady) {
                return;
            }
            Yolo11Detector current;
            synchronized (modelLock) {
                current = detector;
            }
            if (current == null) {
                return;
            }

            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            buffer.rewind();

            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int packedStride = width * 4;

            byte[] rgba = new byte[packedStride * height];
            if (rowStride == packedStride && pixelStride == 4) {
                buffer.get(rgba);
            } else {
                byte[] row = new byte[packedStride];
                for (int y = 0; y < height; y++) {
                    buffer.position(y * rowStride);
                    buffer.get(row, 0, packedStride);
                    System.arraycopy(row, 0, rgba, y * packedStride, packedStride);
                }
            }

            long start = SystemClock.elapsedRealtime();
            float[] detections = current.detect(rgba, width, height, -1f, -1f);
            long costMs = SystemClock.elapsedRealtime() - start;

            float instantFps = costMs > 0 ? 1000f / costMs : 0f;
            smoothedFps = smoothedFps == 0f ? instantFps : smoothedFps * 0.8f + instantFps * 0.2f;
            int count = detections.length / Yolo11Detector.RESULT_STRIDE;

            runOnUiThread(() -> {
                if (videoActive || !isDetectMode()) {
                    return;
                }
                overlayView.setResults(detections, width, height);
                statusText.setText(String.format(Locale.US,
                        "检测  CPU×%d  %.1fFPS  %dms  %d目标",
                        EMBEDDED_THREADS, smoothedFps, costMs, count));
            });
        } catch (Throwable t) {
            runOnUiThread(() -> statusText.setText("推理异常: " + t.getMessage()));
        } finally {
            image.close();
        }
    }

    // ---------------- 视频检测 ----------------

    private void startVideoPlayback(Uri uri) {
        if (!detectorReady) {
            statusText.setText("检测模型加载中，请稍候…");
            return;
        }
        stopVideoPlayback(true);

        videoActive = true;
        videoPaused = false;
        mediaView.setVisibility(View.VISIBLE);
        overlayView.setVisibility(View.VISIBLE);
        btnPickVideo.setVisibility(View.GONE);
        btnPauseVideo.setVisibility(View.VISIBLE);
        btnPauseVideo.setText(R.string.btn_pause_video);
        btnStopVideo.setVisibility(View.VISIBLE);
        statusText.setText("视频加载中…");

        videoThread = new Thread(() -> runVideoDetection(uri), "video-detect");
        videoThread.start();
    }

    private void runVideoDetection(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            long durationMs = 0;
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                durationMs = Long.parseLong(duration);
            }

            long lastCostMs = 0;
            for (long position = 0; position <= durationMs && videoActive; position += VIDEO_FRAME_STEP_MS) {
                while (videoPaused && videoActive) {
                    Thread.sleep(50);
                }
                if (!videoActive) {
                    break;
                }

                Bitmap frame = retriever.getFrameAtTime(
                        position * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) {
                    continue;
                }
                Bitmap scaled = scaleBitmap(frame, 960);
                if (scaled != frame) {
                    frame.recycle();
                }

                byte[] rgba = bitmapToRgba(scaled);
                int width = scaled.getWidth();
                int height = scaled.getHeight();

                Yolo11Detector current;
                synchronized (modelLock) {
                    current = detector;
                }
                if (current == null) {
                    scaled.recycle();
                    break;
                }

                long start = SystemClock.elapsedRealtime();
                float[] detections = current.detect(rgba, width, height, -1f, -1f);
                lastCostMs = SystemClock.elapsedRealtime() - start;

                final long shownPosition = position;
                final long shownCost = lastCostMs;
                final long total = durationMs;
                runOnUiThread(() -> {
                    if (!videoActive) {
                        return;
                    }
                    mediaView.setImageBitmap(scaled);
                    overlayView.setResults(detections, width, height);
                    statusText.setText(String.format(Locale.US,
                            "视频 %.1fs/%.1fs  %dms  %d目标",
                            shownPosition / 1000f, total / 1000f,
                            shownCost, detections.length / Yolo11Detector.RESULT_STRIDE));
                });
            }
        } catch (Throwable ignored) {
            // 视频解码失败（编码不支持等）
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // 忽略释放异常
            }
            runOnUiThread(() -> {
                if (videoActive) {
                    videoThread = null;
                    btnPauseVideo.setVisibility(View.GONE);
                    statusText.setText("视频检测结束，点击「停止」返回实时画面");
                }
            });
        }
    }

    private void toggleVideoPause() {
        videoPaused = !videoPaused;
        btnPauseVideo.setText(videoPaused ? R.string.btn_resume_video : R.string.btn_pause_video);
    }

    private void stopVideoPlayback(boolean clearFrame) {
        videoActive = false;
        videoPaused = false;
        Thread thread = videoThread;
        videoThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        btnPickVideo.setVisibility(View.VISIBLE);
        btnPauseVideo.setVisibility(View.GONE);
        btnStopVideo.setVisibility(View.GONE);
        btnPauseVideo.setText(R.string.btn_pause_video);
        if (clearFrame && mediaView != null) {
            mediaView.setVisibility(View.GONE);
            mediaView.setImageDrawable(null);
            overlayView.clear();
        }
    }

    // ---------------- 图片分类 ----------------

    private void onImagePicked(Uri uri) {
        statusText.setText("正在读取图片…");
        ioExecutor.execute(() -> {
            Bitmap bitmap = null;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream != null) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
            } catch (Exception ignored) {
                // 读取失败按 null 处理
            }
            final Bitmap decoded = bitmap == null ? null : applyExifRotation(bitmap, uri);
            runOnUiThread(() -> {
                if (decoded == null) {
                    statusText.setText("图片读取失败");
                } else {
                    runClassification(decoded);
                }
            });
        });
    }

    private void takePhoto() {
        if (imageCapture == null) {
            statusText.setText("相机未就绪");
            return;
        }
        statusText.setText("正在拍照…");
        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(ImageProxy image) {
                        Bitmap bitmap;
                        try {
                            bitmap = image.toBitmap();
                            int degrees = image.getImageInfo().getRotationDegrees();
                            if (degrees != 0) {
                                bitmap = rotateBitmap(bitmap, degrees);
                            }
                        } finally {
                            image.close();
                        }
                        runClassification(bitmap);
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        statusText.setText("拍照失败: " + exception.getMessage());
                    }
                });
    }

    private void runClassification(Bitmap source) {
        if (!classifierReady) {
            source.recycle();
            statusText.setText("分类模型加载中，请稍候…");
            return;
        }
        final Classifier current;
        synchronized (modelLock) {
            current = classifier;
        }
        if (current == null) {
            source.recycle();
            return;
        }

        Bitmap bitmap = scaleBitmap(source, 1280);
        if (bitmap != source) {
            source.recycle();
        }
        final byte[] rgba = bitmapToRgba(bitmap);
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();

        mediaView.setImageBitmap(bitmap);
        mediaView.setVisibility(View.VISIBLE);
        statusText.setText("分类推理中…");

        ioExecutor.execute(() -> {
            long start = SystemClock.elapsedRealtime();
            float[] results = current.classify(rgba, width, height, current.topk());
            long costMs = SystemClock.elapsedRealtime() - start;

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i + 1 < results.length; i += Classifier.RESULT_STRIDE) {
                int classId = (int) results[i];
                builder.append(i / Classifier.RESULT_STRIDE + 1)
                        .append(". ")
                        .append(current.labelOf(classId))
                        .append("  ")
                        .append(String.format(Locale.US, "%.1f%%", results[i + 1] * 100f))
                        .append('\n');
            }
            String text = builder.length() == 0 ? "无法识别" : builder.toString().trim();
            runOnUiThread(() -> {
                if (isDetectMode()) {
                    return;
                }
                clsResult.setText(text);
                clsResult.setVisibility(View.VISIBLE);
                statusText.setText(String.format(Locale.US,
                        "分类  CPU×%d   %d ms", EMBEDDED_THREADS, costMs));
            });
        });
    }

    // ---------------- 工具 ----------------

    private static Bitmap rotateBitmap(Bitmap source, int degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0,
                source.getWidth(), source.getHeight(), matrix, true);
        if (rotated != source) {
            source.recycle();
        }
        return rotated;
    }

    /** 相册图片的 EXIF 方向需要手动应用，否则分类输入会旋转。 */
    private Bitmap applyExifRotation(Bitmap bitmap, Uri uri) {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                return bitmap;
            }
            ExifInterface exif = new ExifInterface(stream);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                degrees = 90;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                degrees = 180;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                degrees = 270;
            }
            return degrees == 0 ? bitmap : rotateBitmap(bitmap, degrees);
        } catch (Exception ignored) {
            return bitmap;
        }
    }

    private static Bitmap scaleBitmap(Bitmap source, int maxSide) {
        int width = source.getWidth();
        int height = source.getHeight();
        int max = Math.max(width, height);
        if (max <= maxSide) {
            return source;
        }
        float scale = (float) maxSide / max;
        return Bitmap.createScaledBitmap(source,
                Math.round(width * scale), Math.round(height * scale), true);
    }

    private static byte[] bitmapToRgba(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            rgba[i * 4] = (byte) ((color >> 16) & 0xFF);
            rgba[i * 4 + 1] = (byte) ((color >> 8) & 0xFF);
            rgba[i * 4 + 2] = (byte) (color & 0xFF);
            rgba[i * 4 + 3] = (byte) ((color >>> 24) & 0xFF);
        }
        return rgba;
    }

    /**
     * targetSdk 35+ 强制 edge-to-edge：顶部栏叠加状态栏 inset，底部操作栏叠加导航栏 inset。
     */
    private void applyInsets() {
        View topBar = findViewById(R.id.top_bar);
        ViewGroup.MarginLayoutParams topParams =
                (ViewGroup.MarginLayoutParams) topBar.getLayoutParams();
        final int topBaseTop = topParams.topMargin;
        final int topBaseLeft = topParams.leftMargin;
        final int topBaseRight = topParams.rightMargin;
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            lp.topMargin = topBaseTop + bars.top;
            lp.leftMargin = topBaseLeft + bars.left;
            lp.rightMargin = topBaseRight + bars.right;
            view.setLayoutParams(lp);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(topBar);

        for (int id : new int[]{R.id.detect_bar, R.id.cls_bar}) {
            View bar = findViewById(id);
            ViewGroup.MarginLayoutParams barParams =
                    (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
            final int baseBottom = barParams.bottomMargin;
            final int baseLeft = barParams.leftMargin;
            final int baseRight = barParams.rightMargin;
            ViewCompat.setOnApplyWindowInsetsListener(bar, (view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                lp.bottomMargin = baseBottom + bars.bottom;
                lp.leftMargin = baseLeft + bars.left;
                lp.rightMargin = baseRight + bars.right;
                view.setLayoutParams(lp);
                return windowInsets;
            });
            ViewCompat.requestApplyInsets(bar);
        }
    }

    /**
     * 状态面板尺寸/位置变化时同步给 OverlayView，让检测标签自动避开它。
     */
    private void trackHudBoundsForOverlay() {
        statusText.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int[] statusLoc = new int[2];
            int[] overlayLoc = new int[2];
            v.getLocationOnScreen(statusLoc);
            overlayView.getLocationOnScreen(overlayLoc);
            int left = statusLoc[0] - overlayLoc[0];
            int top = statusLoc[1] - overlayLoc[1];
            overlayView.setHudBounds(new Rect(left, top, left + v.getWidth(), top + v.getHeight()));
        });
    }
}
