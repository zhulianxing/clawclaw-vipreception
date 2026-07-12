package com.example.pedestriancapture.vision;

import android.graphics.Rect;

import com.example.pedestriancapture.data.PersonTarget;

public class MatchResult {
    public final boolean matched;
    public final PersonTarget target;
    public final float score;
    public final String mode;

    // v0.7.0 新增：维度评分和检测信息
    public float[] dimensionScores;  // [color, texture, edge, body, pose, aux]
    public Rect personRect;          // 检测到的人物区域（图像坐标）
    public Rect faceRect;            // 检测到的人脸区域（图像坐标）
    public float bestScore;          // 最佳原始分（未平滑）
    public float secondBestScore;    // 第二高分

    private MatchResult(boolean matched, PersonTarget target, float score, String mode) {
        this.matched = matched;
        this.target = target;
        this.score = score;
        this.mode = mode;
    }

    public static MatchResult none() {
        return new MatchResult(false, null, 0f, "无匹配");
    }

    public static MatchResult hit(PersonTarget target, float score, String mode) {
        return new MatchResult(true, target, score, mode);
    }

    /**
     * 设置维度评分
     */
    public MatchResult withDimensions(float color, float texture, float edge, float body, float pose, float aux) {
        this.dimensionScores = new float[]{color, texture, edge, body, pose, aux};
        return this;
    }

    /**
     * 设置检测区域
     */
    public MatchResult withDetection(Rect personRect, Rect faceRect) {
        this.personRect = personRect;
        this.faceRect = faceRect;
        return this;
    }

    /**
     * 设置分数详情
     */
    public MatchResult withScores(float best, float secondBest) {
        this.bestScore = best;
        this.secondBestScore = secondBest;
        return this;
    }
}
