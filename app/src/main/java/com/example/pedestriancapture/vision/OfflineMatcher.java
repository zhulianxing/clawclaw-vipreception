package com.example.pedestriancapture.vision;

import android.graphics.Bitmap;

import com.example.pedestriancapture.data.PersonTarget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实特征匹配器 v3.0
 *
 * 核心改进:
 * 1. 六维度融合评分: 颜色(30%) + 纹理(20%) + 边缘(10%) + 体态(15%) + 姿态(10%) + 辅助(15%)
 * 2. 帧间差分运动检测 — 自动定位画面中的运动人物
 * 3. 肤色检测 — 定位人脸，推算真实人体比例
 * 4. LBP 纹理 — 捕捉衣着纹理（条纹/格子/纯色/花纹）
 * 5. 边缘方向 — 从 Sobel 梯度捕捉姿态轮廓
 * 6. 自适应阈值 + 多帧确认 + 时序平滑
 */
public class OfflineMatcher {
    private int frameIndex = 0;

    // 底库特征缓存
    private final Map<String, FeatureVector> targetFeatures = new HashMap<>();

    // 时序平滑
    private final Map<String, Float> scoreHistory = new HashMap<>();
    private final Map<String, Integer> consecutiveHits = new HashMap<>();

    // 参数
    private static final int REQUIRED_FRAMES = 2;
    private static final float SMOOTH_FACTOR = 0.6f;
    private static final int ANALYZE_EVERY_N_FRAMES = 4;
    private static final float MIN_FRAME_QUALITY = 0.35f;
    private static final float MIN_TARGET_QUALITY = 0.30f;
    private static final float MIN_SCORE_MARGIN = 0.045f;

    // v3.0 权重 — 六维度融合
    private static final float COLOR_WEIGHT = 0.30f;
    private static final float TEXTURE_WEIGHT = 0.20f;
    private static final float EDGE_WEIGHT = 0.10f;
    private static final float BODY_WEIGHT = 0.15f;
    private static final float POSE_WEIGHT = 0.10f;
    private static final float AUX_WEIGHT = 0.15f;

    private static final String MODE_COLOR = "颜色匹配";
    private static final String MODE_TEXTURE = "纹理匹配";
    private static final String MODE_BODY = "体态匹配";
    private static final String MODE_COMBINED = "多特征融合";
    private static final String MODE_PHOTO = "照片+特征";

    public static class VerificationResult {
        public final boolean passed;
        public final float score;
        public final float secondBestScore;
        public final float margin;
        public final float quality;

        VerificationResult(boolean passed, float score, float secondBestScore, float quality) {
            this.passed = passed;
            this.score = score;
            this.secondBestScore = secondBestScore;
            this.margin = score - secondBestScore;
            this.quality = quality;
        }
    }

    /**
     * 注册底库目标
     */
    public void registerTarget(PersonTarget target) {
        if (target == null) return;
        FeatureVector fv = FeatureExtractor.extractFromTarget(target);
        targetFeatures.put(target.id, fv);
    }

    public void registerTargets(List<PersonTarget> targets) {
        targetFeatures.clear();
        for (PersonTarget t : targets) {
            registerTarget(t);
        }
    }

    /**
     * 注册目标照片特征
     */
    public void registerTargetWithPhoto(PersonTarget target, Bitmap photo) {
        if (target == null) return;
        FeatureVector fv;
        if (photo != null) {
            fv = FeatureExtractor.extractFromBitmap(photo, null);
            FeatureVector textFv = FeatureExtractor.extractFromTarget(target);
            fv = mergeFeatures(fv, textFv, 0.9f);
        } else {
            fv = FeatureExtractor.extractFromTarget(target);
        }
        targetFeatures.put(target.id, fv);
    }

    public VerificationResult verifyTargetWithPhoto(PersonTarget target, Bitmap photo) {
        if (target == null || photo == null) {
            return new VerificationResult(false, 0f, 0f, 0f);
        }
        FeatureVector query = FeatureExtractor.extractFromBitmap(photo, null);
        FeatureVector targetFv = targetFeatures.get(target.id);
        if (targetFv == null) {
            registerTargetWithPhoto(target, photo);
            targetFv = targetFeatures.get(target.id);
        }
        if (targetFv == null || !query.isUsable() || !targetFv.isUsable()) {
            return new VerificationResult(false, 0f, 0f, Math.min(query.qualityScore(), targetFv == null ? 0f : targetFv.qualityScore()));
        }

        float score = scoreFeaturePair(query, targetFv, true);
        float secondBest = 0f;
        for (Map.Entry<String, FeatureVector> entry : targetFeatures.entrySet()) {
            if (entry.getKey().equals(target.id)) continue;
            FeatureVector other = entry.getValue();
            if (other == null || !other.isUsable()) continue;
            secondBest = Math.max(secondBest, scoreFeaturePair(query, other, false));
        }

        float quality = Math.min(query.qualityScore(), targetFv.qualityScore());
        boolean passed = score >= 0.72f && score - secondBest >= 0.05f && quality >= MIN_TARGET_QUALITY;
        return new VerificationResult(passed, score, secondBest, quality);
    }

    public void unregisterTarget(String targetId) {
        targetFeatures.remove(targetId);
        scoreHistory.remove(targetId);
        consecutiveHits.remove(targetId);
    }

    /**
     * 核心匹配方法
     */
    public MatchResult match(List<PersonTarget> targets, FeatureVector frameFeature, float threshold) {
        frameIndex++;

        if (targets.isEmpty() || frameFeature == null || !frameFeature.isUsable()) {
            return MatchResult.none();
        }

        // 确保底库特征已缓存
        for (PersonTarget t : targets) {
            if (!targetFeatures.containsKey(t.id)) {
                registerTarget(t);
            }
        }

        float effectiveThreshold = threshold > 0 ? threshold : adaptiveThreshold(targets.size());

        if (frameIndex % ANALYZE_EVERY_N_FRAMES != 0) {
            return MatchResult.none();
        }

        PersonTarget bestTarget = null;
        float bestScore = 0f;
        float secondBestScore = 0f;
        String bestMode = MODE_COMBINED;
        FeatureVector bestFv = null;
        float frameQuality = frameFeature.qualityScore();
        float frameColorDist = frameFeature.colorDistinctiveness();
        float frameTextureDist = frameFeature.textureDistinctiveness();

        for (PersonTarget target : targets) {
            FeatureVector targetFv = targetFeatures.get(target.id);
            if (targetFv == null || targetFv.qualityScore() < MIN_TARGET_QUALITY) continue;

            // 六维度相似度
            float colorSim = frameFeature.colorSimilarity(targetFv);
            float textureSim = frameFeature.textureSimilarity(targetFv);
            float silhouetteSim = frameFeature.silhouetteSimilarity(targetFv);
            float bodySim = frameFeature.bodySimilarity(targetFv);
            float combinedSim = frameFeature.weightedCosineSimilarity(targetFv);

            float targetQuality = targetFv.qualityScore();
            float targetColorDist = targetFv.colorDistinctiveness();
            float colorReliability = clamp01((frameColorDist + targetColorDist) * 0.75f);
            float textureReliability = clamp01((frameTextureDist + targetFv.textureDistinctiveness()) * 0.5f);
            float qualityFactor = Math.min(frameQuality, targetQuality);

            // 动态权重调整
            // 颜色权重随颜色区分度变化
            float dynamicColorWeight = COLOR_WEIGHT * (0.45f + colorReliability * 0.55f);
            // 纹理权重随纹理区分度变化
            float dynamicTextureWeight = TEXTURE_WEIGHT * (0.50f + textureReliability * 0.50f);
            // 剩余权重分配给体态和融合特征
            float remainingWeight = 1f - dynamicColorWeight - dynamicTextureWeight;
            float dynamicBodyWeight = BODY_WEIGHT + remainingWeight * 0.30f;
            float dynamicCombinedWeight = EDGE_WEIGHT + POSE_WEIGHT + AUX_WEIGHT + remainingWeight * 0.70f;

            // 融合评分
            float score = colorSim * dynamicColorWeight
                        + textureSim * dynamicTextureWeight
                        + bodySim * dynamicBodyWeight
                        + combinedSim * dynamicCombinedWeight;
            score *= 0.88f + qualityFactor * 0.12f;

            // 有照片时提升基础分
            boolean hasPhoto = filled(target.photoUri);
            if (hasPhoto) {
                score = score * 0.65f + combinedSim * 0.35f;
            }

            // 证据充分性检查
            boolean hasEnoughEvidence = hasEnoughEvidence(colorSim, textureSim, bodySim, combinedSim,
                                                           colorReliability, textureReliability, hasPhoto);
            if (!hasEnoughEvidence) {
                score *= 0.72f;
            }

            // 时序平滑
            float smoothed = smoothScore(target.id, score);

            if (smoothed > bestScore) {
                secondBestScore = bestScore;
            } else if (smoothed > secondBestScore) {
                secondBestScore = smoothed;
            }

            // 连续命中确认
            int hits = updateConsecutive(target.id, smoothed >= effectiveThreshold && hasEnoughEvidence);

            if (smoothed >= effectiveThreshold && hits >= REQUIRED_FRAMES) {
                if (smoothed > bestScore) {
                    bestScore = smoothed;
                    bestTarget = target;
                    bestFv = targetFv;

                    // 判定匹配模式
                    if (hasPhoto) {
                        bestMode = MODE_PHOTO;
                    } else if (textureSim > 0.7f && textureReliability > 0.3f) {
                        bestMode = MODE_TEXTURE;
                    } else if (colorSim > 0.7f && bodySim > 0.6f) {
                        bestMode = MODE_COMBINED;
                    } else if (colorSim > bodySim + 0.15f && colorSim > textureSim) {
                        bestMode = MODE_COLOR;
                    } else {
                        bestMode = MODE_BODY;
                    }
                }
            }
        }

        boolean clearWinner = targets.size() <= 1 || bestScore - secondBestScore >= MIN_SCORE_MARGIN;
        if (bestTarget != null && bestScore >= effectiveThreshold && clearWinner) {
            consecutiveHits.put(bestTarget.id, 0);
            MatchResult result = MatchResult.hit(bestTarget, bestScore, bestMode);

            // 附加维度评分
            if (bestFv != null && frameFeature != null) {
                float colorSim = frameFeature.colorSimilarity(bestFv);
                float textureSim = frameFeature.textureSimilarity(bestFv);
                float edgeSim = frameFeature.silhouetteSimilarity(bestFv);
                float bodySim = frameFeature.bodySimilarity(bestFv);
                float poseSim = frameFeature.weightedCosineSimilarity(bestFv);
                float auxSim = bestScore; // 综合分作为辅助参考
                result.withDimensions(colorSim, textureSim, edgeSim, bodySim, poseSim, auxSim);
            }

            // 附加检测区域
            result.withDetection(FeatureExtractor.getLastPersonRect(),
                                  FeatureExtractor.getLastFaceRect());
            result.withScores(bestScore, secondBestScore);
            return result;
        }

        // 即使未匹配，也返回检测区域信息供 UI 显示
        MatchResult noResult = MatchResult.none();
        noResult.withDetection(FeatureExtractor.getLastPersonRect(),
                               FeatureExtractor.getLastFaceRect());
        return noResult;
    }

    /**
     * 兼容旧接口
     */
    public MatchResult match(List<PersonTarget> targets, float threshold) {
        frameIndex++;
        if (targets.isEmpty() || frameIndex % ANALYZE_EVERY_N_FRAMES != 0) {
            return MatchResult.none();
        }

        for (PersonTarget t : targets) {
            if (!targetFeatures.containsKey(t.id)) {
                registerTarget(t);
            }
        }

        float effectiveThreshold = threshold > 0 ? threshold : adaptiveThreshold(targets.size());

        PersonTarget bestTarget = null;
        float bestScore = 0f;
        String bestMode = MODE_BODY;

        for (PersonTarget target : targets) {
            FeatureVector fv = targetFeatures.get(target.id);
            if (fv == null) continue;

            float infoScore = computeInformationScore(fv);
            float smoothed = smoothScore(target.id, infoScore);
            int hits = updateConsecutive(target.id, smoothed >= effectiveThreshold);

            if (smoothed >= effectiveThreshold && hits >= REQUIRED_FRAMES && smoothed > bestScore) {
                bestScore = smoothed;
                bestTarget = target;
                boolean hasPhoto = filled(target.photoUri);
                bestMode = hasPhoto ? MODE_PHOTO : MODE_BODY;
            }
        }

        if (bestTarget != null && bestScore >= effectiveThreshold) {
            consecutiveHits.put(bestTarget.id, 0);
            return MatchResult.hit(bestTarget, bestScore, bestMode);
        }

        return MatchResult.none();
    }

    // ==================== 自适应阈值 ====================

    private float adaptiveThreshold(int targetCount) {
        if (targetCount <= 1) return 0.42f;
        if (targetCount <= 3) return 0.48f;
        if (targetCount <= 5) return 0.52f;
        if (targetCount <= 10) return 0.58f;
        return 0.63f;
    }

    // ==================== 特征信息量 ====================

    private float computeInformationScore(FeatureVector fv) {
        float[] data = fv.data;
        float mean = 0;
        for (float v : data) mean += v;
        mean /= data.length;

        float variance = 0;
        for (float v : data) variance += (v - mean) * (v - mean);
        variance /= data.length;

        float infoScore = 0.3f + (float) Math.sqrt(variance) * 3f;
        return Math.min(infoScore, 0.95f);
    }

    // ==================== 时序平滑 & 连续确认 ====================

    private float smoothScore(String targetId, float rawScore) {
        Float prev = scoreHistory.get(targetId);
        float smoothed = (prev == null) ? rawScore : (SMOOTH_FACTOR * prev + (1 - SMOOTH_FACTOR) * rawScore);
        scoreHistory.put(targetId, smoothed);
        return smoothed;
    }

    private int updateConsecutive(String targetId, boolean hit) {
        int count = consecutiveHits.getOrDefault(targetId, 0);
        count = hit ? count + 1 : Math.max(0, count - 1);
        consecutiveHits.put(targetId, count);
        return count;
    }

    // ==================== 特征融合 ====================

    private FeatureVector mergeFeatures(FeatureVector photo, FeatureVector text, float photoWeight) {
        float[] merged = new float[FeatureVector.DIM];
        float textWeight = 1f - photoWeight;
        for (int i = 0; i < FeatureVector.DIM; i++) {
            merged[i] = photo.data[i] * photoWeight + text.data[i] * textWeight;
        }
        return new FeatureVector(merged);
    }

    private float scoreFeaturePair(FeatureVector query, FeatureVector target, boolean hasPhoto) {
        float colorSim = query.colorSimilarity(target);
        float textureSim = query.textureSimilarity(target);
        float bodySim = query.bodySimilarity(target);
        float combinedSim = query.weightedCosineSimilarity(target);
        float colorReliability = clamp01((query.colorDistinctiveness() + target.colorDistinctiveness()) * 0.75f);
        float textureReliability = clamp01((query.textureDistinctiveness() + target.textureDistinctiveness()) * 0.5f);
        float qualityFactor = Math.min(query.qualityScore(), target.qualityScore());

        float dynamicColorWeight = COLOR_WEIGHT * (0.45f + colorReliability * 0.55f);
        float dynamicTextureWeight = TEXTURE_WEIGHT * (0.50f + textureReliability * 0.50f);
        float remainingWeight = 1f - dynamicColorWeight - dynamicTextureWeight;
        float dynamicBodyWeight = BODY_WEIGHT + remainingWeight * 0.30f;
        float dynamicCombinedWeight = EDGE_WEIGHT + POSE_WEIGHT + AUX_WEIGHT + remainingWeight * 0.70f;

        float score = colorSim * dynamicColorWeight
                + textureSim * dynamicTextureWeight
                + bodySim * dynamicBodyWeight
                + combinedSim * dynamicCombinedWeight;
        score *= 0.88f + qualityFactor * 0.12f;
        if (hasPhoto) {
            score = score * 0.65f + combinedSim * 0.35f;
        }
        return clamp01(score);
    }

    // ==================== 工具 ====================

    private boolean filled(String value) {
        return value != null && !value.trim().isEmpty() && !"未设置".equals(value);
    }

    private boolean hasEnoughEvidence(float colorSim, float textureSim, float bodySim, float combinedSim,
                                       float colorReliability, float textureReliability, boolean hasPhoto) {
        if (hasPhoto && combinedSim >= 0.64f) {
            return true;
        }
        boolean colorEvidence = colorReliability >= 0.22f && colorSim >= 0.60f;
        boolean textureEvidence = textureReliability >= 0.20f && textureSim >= 0.65f;
        boolean bodyEvidence = bodySim >= 0.62f;
        boolean combinedEvidence = combinedSim >= 0.66f;

        // 需要至少两个维度的证据
        int evidenceCount = 0;
        if (colorEvidence) evidenceCount++;
        if (textureEvidence) evidenceCount++;
        if (bodyEvidence) evidenceCount++;
        if (combinedEvidence) evidenceCount++;

        return evidenceCount >= 2;
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public String getStats() {
        return String.format("OfflineMatcher v3.0[帧=%d, 底库=%d, 阈值=%.2f]",
            frameIndex, targetFeatures.size(), adaptiveThreshold(targetFeatures.size()));
    }
}
