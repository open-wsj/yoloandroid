package com.example.yolo11ncnn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 在预览画面上绘制检测框。坐标映射按 PreviewView 的 FIT_CENTER（完整显示、留黑边）处理。
 */
public class OverlayView extends View {

    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint textBgPaint = new Paint();

    private float[] detections = new float[0];
    private String[] labels = new String[0];
    private int imageWidth = 0;
    private int imageHeight = 0;

    // 左上角状态面板占用的区域（本视图坐标系），标签需要避开它
    private final Rect hudBounds = new Rect();
    private boolean hasHudBounds = false;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * 设置需要避让的 HUD 区域，传 null 表示不避让。
     */
    public void setHudBounds(Rect bounds) {
        hasHudBounds = bounds != null && !bounds.isEmpty();
        if (hasHudBounds) {
            hudBounds.set(bounds);
        }
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(dp(2.5f));
        boxPaint.setAntiAlias(true);

        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setAntiAlias(true);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(13f));
        textPaint.setAntiAlias(true);
        textPaint.setFakeBoldText(true);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setLabels(String[] labels) {
        this.labels = labels == null ? new String[0] : labels;
    }

    public void setResults(float[] detections, int imageWidth, int imageHeight) {
        this.detections = detections == null ? new float[0] : detections;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        postInvalidate();
    }

    public void clear() {
        detections = new float[0];
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageWidth <= 0 || imageHeight <= 0 || detections.length == 0) {
            return;
        }

        float scale = Math.min((float) getWidth() / imageWidth, (float) getHeight() / imageHeight);
        float offsetX = (getWidth() - imageWidth * scale) * 0.5f;
        float offsetY = (getHeight() - imageHeight * scale) * 0.5f;

        List<RectF> occupiedLabels = new ArrayList<>();

        for (int i = 0; i + Yolo11Detector.RESULT_STRIDE <= detections.length;
             i += Yolo11Detector.RESULT_STRIDE) {
            float x1 = detections[i] * scale + offsetX;
            float y1 = detections[i + 1] * scale + offsetY;
            float x2 = detections[i + 2] * scale + offsetX;
            float y2 = detections[i + 3] * scale + offsetY;
            float score = detections[i + 4];
            int classId = (int) detections[i + 5];

            int color = colorForClass(classId);
            boxPaint.setColor(color);
            canvas.drawRect(x1, y1, x2, y2, boxPaint);

            String label = (classId >= 0 && classId < labels.length ? labels[classId] : String.valueOf(classId))
                    + " " + String.format(java.util.Locale.US, "%.2f", score);

            Rect bounds = new Rect();
            textPaint.getTextBounds(label, 0, label.length(), bounds);
            float textHeight = bounds.height() + dp(6f);
            float textWidth = textPaint.measureText(label) + dp(8f);

            // 标签默认画在框上方，越界时回退到框内
            float textLeft = x1;
            float textTop = y1 - textHeight < 0 ? y1 : y1 - textHeight;

            // 限制在视图内，避免贴边被截断
            textLeft = Math.max(0f, Math.min(textLeft, getWidth() - textWidth));
            textTop = Math.max(0f, Math.min(textTop, getHeight() - textHeight));

            // 与左上角状态面板重叠时下移到面板下方
            if (hasHudBounds
                    && textLeft < hudBounds.right && textLeft + textWidth > hudBounds.left
                    && textTop < hudBounds.bottom && textTop + textHeight > hudBounds.top) {
                textTop = hudBounds.bottom + dp(4f);
            }
            // 下移后仍超出底部则本帧不画标签（检测框照常绘制）
            if (textTop + textHeight > getHeight()) {
                continue;
            }

            // 与其他标签重叠时逐次下移，避免文字互相压盖
            RectF labelRect = new RectF(textLeft, textTop,
                    textLeft + textWidth, textTop + textHeight);
            for (int attempt = 0; attempt < 4; attempt++) {
                boolean collides = false;
                for (RectF other : occupiedLabels) {
                    if (RectF.intersects(other, labelRect)) {
                        collides = true;
                        break;
                    }
                }
                if (!collides) {
                    break;
                }
                labelRect.offset(0f, textHeight + dp(2f));
            }
            if (labelRect.bottom > getHeight()) {
                continue;
            }
            occupiedLabels.add(labelRect);

            textBgPaint.setColor((color & 0x00FFFFFF) | 0xCC000000);
            canvas.drawRect(labelRect, textBgPaint);
            canvas.drawText(label, labelRect.left + dp(4f),
                    labelRect.bottom - dp(4f), textPaint);
        }
    }

    private int colorForClass(int classId) {
        int[] palette = {
                0xFFFF5252, 0xFF40C4FF, 0xFF69F0AE, 0xFFFFD740,
                0xFFE040FB, 0xFFFF6E40, 0xFF64FFDA, 0xFFB2FF59
        };
        return palette[Math.abs(classId) % palette.length];
    }
}
