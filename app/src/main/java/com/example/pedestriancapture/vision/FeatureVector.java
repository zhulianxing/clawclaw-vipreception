package com.example.pedestriancapture.vision;

import java.util.Arrays;

/**
 * 人物特征向量 v3.0
 *
 * 维度说明 (共 128 维):
 * - [0..47]   颜色直方图: 多区域 HSV (上半身/下半身/头部)，16 H-bins × 3 zones
 * - [48..63]  LBP 纹理直方图: 16 bins，捕捉衣着纹理 (条纹/格子/纯色等)
 * - [64..72]  边缘方向直方图: 9 bins，从 Sobel 梯度捕捉姿态轮廓
 * - [73..80]  人体比例: 肩宽比、腰臀比、身高比例、腿身比、臂展比、头身比、肩腰比、胸腰比
 * - [81..92]  姿态关键点归一化: 鼻/肩/髋/膝 的 x,y (6个关键点×2)
 * - [93..96]  辅助特征: 体型分类、性别概率、年龄估计、姿态角度
 *
 * 特征提取完全离线，无需网络
 */
public class FeatureVector {
    public static final int DIM = 128;

    // 维度分区常量
    public static final int COLOR_OFFSET = 0;
    public static final int COLOR_DIM = 48;
    public static final int TEXTURE_OFFSET = 48;
    public static final int TEXTURE_DIM = 16;
    public static final int EOH_OFFSET = 64;
    public static final int EOH_DIM = 9;
    public static final int BODY_OFFSET = 73;
    public static final int BODY_DIM = 8;
    public static final int POSE_OFFSET = 81;
    public static final int POSE_DIM = 12;
    public static final int AUX_OFFSET = 93;
    public static final int AUX_DIM = 4;
    // 填充到 128 维
    public static final int PADDING_OFFSET = 97;
    public static final int PADDING_DIM = 31;

    public final float[] data;
    public final long timestamp;

    public FeatureVector() {
        this.data = new float[DIM];
        this.timestamp = System.currentTimeMillis();
    }

    public FeatureVector(float[] data) {
        if (data.length != DIM) throw new IllegalArgumentException("Expected " + DIM + " dims, got " + data.length);
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ====== 颜色直方图 (48 dims) ======

    public void setColorHistogram(float[] histogram) {
        if (histogram.length != COLOR_DIM) return;
        System.arraycopy(histogram, 0, data, COLOR_OFFSET, COLOR_DIM);
    }

    public float[] getColorHistogram() {
        return Arrays.copyOfRange(data, COLOR_OFFSET, COLOR_OFFSET + COLOR_DIM);
    }

    // ====== LBP 纹理直方图 (16 dims) ======

    public void setTextureHistogram(float[] histogram) {
        if (histogram.length != TEXTURE_DIM) return;
        System.arraycopy(histogram, 0, data, TEXTURE_OFFSET, TEXTURE_DIM);
    }

    public float[] getTextureHistogram() {
        return Arrays.copyOfRange(data, TEXTURE_OFFSET, TEXTURE_OFFSET + TEXTURE_DIM);
    }

    // ====== 边缘方向直方图 (9 dims) ======

    public void setEdgeOrientation(float[] histogram) {
        if (histogram.length != EOH_DIM) return;
        System.arraycopy(histogram, 0, data, EOH_OFFSET, EOH_DIM);
    }

    public float[] getEdgeOrientation() {
        return Arrays.copyOfRange(data, EOH_OFFSET, EOH_OFFSET + EOH_DIM);
    }

    // ====== 人体比例 (8 dims) ======

    public void setBodyRatios(float shoulderWidthRatio, float waistHipRatio,
                               float heightRatio, float legBodyRatio,
                               float armSpanRatio, float headBodyRatio,
                               float shoulderWaistRatio, float chestWaistRatio) {
        data[BODY_OFFSET] = shoulderWidthRatio;
        data[BODY_OFFSET + 1] = waistHipRatio;
        data[BODY_OFFSET + 2] = heightRatio;
        data[BODY_OFFSET + 3] = legBodyRatio;
        data[BODY_OFFSET + 4] = armSpanRatio;
        data[BODY_OFFSET + 5] = headBodyRatio;
        data[BODY_OFFSET + 6] = shoulderWaistRatio;
        data[BODY_OFFSET + 7] = chestWaistRatio;
    }

    public float[] getBodyRatios() {
        return Arrays.copyOfRange(data, BODY_OFFSET, BODY_OFFSET + BODY_DIM);
    }

    // ====== 姿态关键点 (12 dims) ======

    public void setPoseKeypoints(float[] keypoints) {
        if (keypoints.length < 12) return;
        System.arraycopy(keypoints, 0, data, POSE_OFFSET, POSE_DIM);
    }

    public float[] getPoseKeypoints() {
        return Arrays.copyOfRange(data, POSE_OFFSET, POSE_OFFSET + POSE_DIM);
    }

    // ====== 辅助特征 (4 dims) ======

    public void setAuxiliaryFeatures(float bodyType, float genderProb, float ageEst, float poseAngle) {
        data[AUX_OFFSET] = bodyType;
        data[AUX_OFFSET + 1] = genderProb;
        data[AUX_OFFSET + 2] = ageEst;
        data[AUX_OFFSET + 3] = poseAngle;
    }

    public float[] getAuxiliaryFeatures() {
        return Arrays.copyOfRange(data, AUX_OFFSET, AUX_OFFSET + AUX_DIM);
    }

    // ====== 相似度计算 ======

    /**
     * 加权余弦相似度 v3.0
     * 权重分配: 颜色 30%, 纹理 20%, 边缘 10%, 人体比例 15%, 姿态 10%, 辅助 15%
     */
    public float weightedCosineSimilarity(FeatureVector other) {
        float colorSim = cosineSimilarity(this.data, other.data, COLOR_OFFSET, COLOR_DIM);
        float textureSim = cosineSimilarity(this.data, other.data, TEXTURE_OFFSET, TEXTURE_DIM);
        float eohSim = cosineSimilarity(this.data, other.data, EOH_OFFSET, EOH_DIM);
        float bodySim = cosineSimilarity(this.data, other.data, BODY_OFFSET, BODY_DIM);
        float poseSim = cosineSimilarity(this.data, other.data, POSE_OFFSET, POSE_DIM);
        float auxSim = cosineSimilarity(this.data, other.data, AUX_OFFSET, AUX_DIM);

        return colorSim * 0.30f + textureSim * 0.20f + eohSim * 0.10f
             + bodySim * 0.15f + poseSim * 0.10f + auxSim * 0.15f;
    }

    /**
     * 标准余弦相似度 (全向量)
     */
    public float cosineSimilarity(FeatureVector other) {
        return cosineSimilarity(this.data, other.data, 0, DIM);
    }

    /**
     * 颜色相似度 (仅颜色直方图部分)
     */
    public float colorSimilarity(FeatureVector other) {
        return cosineSimilarity(this.data, other.data, COLOR_OFFSET, COLOR_DIM);
    }

    /**
     * 纹理相似度
     */
    public float textureSimilarity(FeatureVector other) {
        return cosineSimilarity(this.data, other.data, TEXTURE_OFFSET, TEXTURE_DIM);
    }

    /**
     * 体态相似度 (人体比例 + 辅助特征)
     */
    public float bodySimilarity(FeatureVector other) {
        float ratioSim = cosineSimilarity(this.data, other.data, BODY_OFFSET, BODY_DIM);
        float auxSim = cosineSimilarity(this.data, other.data, AUX_OFFSET, AUX_DIM);
        return ratioSim * 0.7f + auxSim * 0.3f;
    }

    /**
     * 轮廓相似度 (边缘方向 + 姿态)
     */
    public float silhouetteSimilarity(FeatureVector other) {
        float eohSim = cosineSimilarity(this.data, other.data, EOH_OFFSET, EOH_DIM);
        float poseSim = cosineSimilarity(this.data, other.data, POSE_OFFSET, POSE_DIM);
        return eohSim * 0.5f + poseSim * 0.5f;
    }

    /**
     * 颜色区分度
     */
    public float colorDistinctiveness() {
        float max = 0f;
        float sum = 0f;
        float energy = 0f;
        for (int i = COLOR_OFFSET; i < COLOR_OFFSET + COLOR_DIM; i++) {
            float v = Math.abs(data[i]);
            max = Math.max(max, v);
            sum += v;
            energy += v * v;
        }
        if (sum <= 0f) return 0f;
        float concentration = max / sum;
        float normalizedEnergy = (float) Math.sqrt(energy);
        return clamp01(concentration * 0.65f + normalizedEnergy * 0.35f);
    }

    /**
     * 纹理区分度
     */
    public float textureDistinctiveness() {
        float max = 0f;
        float sum = 0f;
        for (int i = TEXTURE_OFFSET; i < TEXTURE_OFFSET + TEXTURE_DIM; i++) {
            float v = Math.abs(data[i]);
            max = Math.max(max, v);
            sum += v;
        }
        if (sum <= 0f) return 0f;
        return clamp01(max / sum);
    }

    /**
     * 特征整体质量
     */
    public float qualityScore() {
        float colorMass = 0f;
        for (int i = COLOR_OFFSET; i < COLOR_OFFSET + COLOR_DIM; i++) colorMass += Math.abs(data[i]);

        float textureMass = 0f;
        for (int i = TEXTURE_OFFSET; i < TEXTURE_OFFSET + TEXTURE_DIM; i++) textureMass += Math.abs(data[i]);

        float eohMass = 0f;
        for (int i = EOH_OFFSET; i < EOH_OFFSET + EOH_DIM; i++) eohMass += Math.abs(data[i]);

        float bodyMass = 0f;
        for (int i = BODY_OFFSET; i < BODY_OFFSET + BODY_DIM; i++) bodyMass += Math.abs(data[i]);

        float colorQuality = clamp01(colorMass);
        float textureQuality = clamp01(textureMass);
        float eohQuality = clamp01(eohMass);
        float bodyQuality = clamp01(bodyMass / 5.5f);

        return colorQuality * 0.35f + textureQuality * 0.20f + eohQuality * 0.15f + bodyQuality * 0.30f;
    }

    public boolean isUsable() {
        return qualityScore() >= 0.35f;
    }

    // ====== 工具方法 ======

    private static float cosineSimilarity(float[] a, float[] b, int offset, int len) {
        float dotProduct = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < len; i++) {
            float va = a[offset + i];
            float vb = b[offset + i];
            dotProduct += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        if (normA == 0 || normB == 0) return 0f;
        return (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    /**
     * 欧氏距离 (归一化)
     */
    public float euclideanDistance(FeatureVector other) {
        float sum = 0f;
        for (int i = 0; i < DIM; i++) {
            float d = data[i] - other.data[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum / DIM);
    }

    /**
     * Bhattacharyya 距离 (适用于直方图比较)
     */
    public float bhattacharyyaDistance(float[] histA, float[] histB) {
        float sum = 0f;
        for (int i = 0; i < histA.length; i++) {
            sum += Math.sqrt(histA[i] * histB[i]);
        }
        return (float) Math.sqrt(Math.max(0, 1 - sum));
    }

    @Override
    public String toString() {
        return String.format("FV[色=%.3f, 纹=%.3f, 边=%.3f, 体=%.3f, 姿=%.3f]",
            avg(COLOR_OFFSET, COLOR_DIM), avg(TEXTURE_OFFSET, TEXTURE_DIM),
            avg(EOH_OFFSET, EOH_DIM), avg(BODY_OFFSET, BODY_DIM), avg(POSE_OFFSET, POSE_DIM));
    }

    private float avg(int offset, int len) {
        float s = 0;
        for (int i = offset; i < offset + len; i++) s += Math.abs(data[i]);
        return s / len;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
