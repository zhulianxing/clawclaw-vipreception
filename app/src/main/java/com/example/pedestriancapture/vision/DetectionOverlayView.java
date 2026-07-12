package com.example.pedestriancapture.vision;

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
 * 检测叠加层 View — 在相机预览上绘制检测框、人脸框、匹配信息
 */
public class DetectionOverlayView extends View {

    private final List<DrawInfo> drawList = new ArrayList<>();
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int previewWidth = 0;
    private int previewHeight = 0;

    public DetectionOverlayView(Context context) {
        super(context);
        init();
    }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);

        textBgPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28f);
        textPaint.setFakeBoldText(true);

        dotPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * 设置预览尺寸（用于坐标映射）
     */
    public void setPreviewSize(int w, int h) {
        this.previewWidth = w;
        this.previewHeight = h;
    }

    /**
     * 更新检测结果
     */
    public void updateDetection(DetectionResult result) {
        drawList.clear();
        if (result == null) {
            invalidate();
            return;
        }

        // 1. 运动/人体检测框
        if (result.personRect != null) {
            DrawInfo info = new DrawInfo();
            info.rect = mapRect(result.personRect);
            info.color = result.matched ? Color.parseColor("#22C55E") : Color.parseColor("#FBBF24");
            info.label = result.matched ? "✓ " + result.targetName : "检测中";
            info.type = DrawType.PERSON;
            drawList.add(info);
        }

        // 2. 人脸框
        if (result.faceRect != null) {
            DrawInfo faceInfo = new DrawInfo();
            faceInfo.rect = mapRect(result.faceRect);
            faceInfo.color = Color.parseColor("#3B82F6");
            faceInfo.label = "人脸";
            faceInfo.type = DrawType.FACE;
            drawList.add(faceInfo);
        }

        // 3. 维度评分条
        if (result.matched && result.dimensionScores != null) {
            DrawInfo scoreInfo = new DrawInfo();
            scoreInfo.type = DrawType.SCORES;
            scoreInfo.dimensionScores = result.dimensionScores;
            scoreInfo.dimensionLabels = result.dimensionLabels;
            scoreInfo.totalScore = result.totalScore;
            scoreInfo.rect = mapRect(result.personRect);
            drawList.add(scoreInfo);
        }

        invalidate();
    }

    /**
     * 将图像坐标映射到 View 坐标
     */
    private RectF mapRect(Rect src) {
        if (previewWidth == 0 || previewHeight == 0 || src == null) return null;
        float scaleX = (float) getWidth() / previewWidth;
        float scaleY = (float) getHeight() / previewHeight;
        return new RectF(
            src.left * scaleX,
            src.top * scaleY,
            src.right * scaleX,
            src.bottom * scaleY
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (DrawInfo info : drawList) {
            switch (info.type) {
                case PERSON:
                    drawPersonBox(canvas, info);
                    break;
                case FACE:
                    drawFaceBox(canvas, info);
                    break;
                case SCORES:
                    drawDimensionScores(canvas, info);
                    break;
            }
        }
    }

    private void drawPersonBox(Canvas canvas, DrawInfo info) {
        if (info.rect == null) return;
        boxPaint.setColor(info.color);
        boxPaint.setStrokeWidth(4f);
        canvas.drawRoundRect(info.rect, 12f, 12f, boxPaint);

        // 标签背景
        if (info.label != null) {
            float labelW = textPaint.measureText(info.label) + 20f;
            float labelH = 36f;
            textBgPaint.setColor(info.color);
            canvas.drawRoundRect(
                new RectF(info.rect.left, info.rect.top - labelH, info.rect.left + labelW, info.rect.top),
                6f, 6f, textBgPaint);
            canvas.drawText(info.label, info.rect.left + 10f, info.rect.top - 8f, textPaint);
        }
    }

    private void drawFaceBox(Canvas canvas, DrawInfo info) {
        if (info.rect == null) return;
        boxPaint.setColor(info.color);
        boxPaint.setStrokeWidth(2.5f);
        canvas.drawRoundRect(info.rect, 8f, 8f, boxPaint);

        // 角标
        float cornerLen = Math.min(info.rect.width(), info.rect.height()) * 0.2f;
        boxPaint.setStrokeWidth(3f);
        // 左上
        canvas.drawLine(info.rect.left, info.rect.top, info.rect.left + cornerLen, info.rect.top, boxPaint);
        canvas.drawLine(info.rect.left, info.rect.top, info.rect.left, info.rect.top + cornerLen, boxPaint);
        // 右上
        canvas.drawLine(info.rect.right, info.rect.top, info.rect.right - cornerLen, info.rect.top, boxPaint);
        canvas.drawLine(info.rect.right, info.rect.top, info.rect.right, info.rect.top + cornerLen, boxPaint);
    }

    private void drawDimensionScores(Canvas canvas, DrawInfo info) {
        if (info.dimensionScores == null || info.rect == null) return;

        float barX = info.rect.right + 12f;
        float barY = info.rect.top;
        float barW = 120f;
        float barH = 16f;
        float gap = 6f;

        // 半透明背景面板
        float panelH = info.dimensionScores.length * (barH + gap) + 40f;
        textBgPaint.setColor(Color.parseColor("#CC000000"));
        canvas.drawRoundRect(new RectF(barX - 8f, barY - 4f, barX + barW + 50f, barY + panelH),
            8f, 8f, textBgPaint);

        textPaint.setTextSize(20f);
        textPaint.setColor(Color.WHITE);

        for (int i = 0; i < info.dimensionScores.length; i++) {
            float y = barY + i * (barH + gap);
            // 标签
            if (info.dimensionLabels != null && i < info.dimensionLabels.length) {
                canvas.drawText(info.dimensionLabels[i], barX, y + barH - 4f, textPaint);
            }
            // 分数条
            float scoreW = barW * info.dimensionScores[i];
            int scoreColor = info.dimensionScores[i] > 0.65f ?
                Color.parseColor("#22C55E") :
                info.dimensionScores[i] > 0.45f ?
                    Color.parseColor("#FBBF24") : Color.parseColor("#EF4444");
            dotPaint.setColor(scoreColor);
            canvas.drawRoundRect(new RectF(barX + 60f, y, barX + 60f + scoreW, y + barH),
                3f, 3f, dotPaint);
        }

        // 总分
        float totalY = barY + info.dimensionScores.length * (barH + gap) + 8f;
        textPaint.setTextSize(24f);
        textPaint.setColor(Color.parseColor("#22C55E"));
        canvas.drawText(String.format("总分 %d%%", Math.round(info.totalScore * 100)),
            barX, totalY, textPaint);
    }

    /**
     * 清除
     */
    public void clear() {
        drawList.clear();
        invalidate();
    }

    // ==================== 数据结构 ====================

    private enum DrawType { PERSON, FACE, SCORES }

    private static class DrawInfo {
        DrawType type;
        RectF rect;
        int color;
        String label;
        float[] dimensionScores;
        String[] dimensionLabels;
        float totalScore;
    }

    /**
     * 检测结果数据
     */
    public static class DetectionResult {
        public Rect personRect;      // 图像坐标系
        public Rect faceRect;        // 图像坐标系
        public boolean matched;
        public String targetName;
        public float totalScore;
        public float[] dimensionScores;  // 6个维度
        public String[] dimensionLabels; // 维度名称
    }
}
