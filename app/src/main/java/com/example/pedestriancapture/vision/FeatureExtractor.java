package com.example.pedestriancapture.vision;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;

import com.example.pedestriancapture.data.PersonTarget;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 特征提取器 v3.0
 *
 * 核心改进:
 * 1. 帧间差分运动检测 — 自动定位画面中的运动区域作为人物 ROI
 * 2. 肤色检测 — 定位人脸/手部区域，推算真实人体比例
 * 3. LBP 纹理直方图 — 捕捉衣着纹理特征（条纹/格子/纯色等）
 * 4. 边缘方向直方图 (EOH) — 从梯度方向捕捉姿态轮廓
 * 5. 多区域颜色采样 — 上半身/下半身/头部三区独立颜色签名
 * 6. 丰富的文本→颜色映射 — 支持复合颜色描述
 *
 * 完全离线运行，无任何模型依赖
 */
public class FeatureExtractor {

    // HSV 直方图参数
    private static final int H_BINS = 16;
    private static final int TONE_BINS = 16;

    // LBP 纹理参数
    private static final int LBP_BINS = 16;

    // 边缘方向参数
    private static final int EOH_BINS = 9; // 0°, 20°, 40°, ... 160°

    // 检测参数
    private static final float DEFAULT_ROI_LEFT = 0.22f;
    private static final float DEFAULT_ROI_TOP = 0.10f;
    private static final float DEFAULT_ROI_RIGHT = 0.78f;
    private static final float DEFAULT_ROI_BOTTOM = 0.94f;

    // 帧间差分缓存（运动检测）
    private static Bitmap prevFrame = null;
    private static int prevFrameWidth = 0;
    private static int prevFrameHeight = 0;

    // 肤色范围 (YCrCb)
    private static final int CR_MIN = 133, CR_MAX = 173;
    private static final int CB_MIN = 77, CB_MAX = 127;

    // 最近一次检测的区域（供 UI 叠加层使用）
    private static Rect lastPersonRect = null;
    private static Rect lastFaceRect = null;

    /** 获取最近一次检测的人物区域 */
    public static Rect getLastPersonRect() { return lastPersonRect; }
    /** 获取最近一次检测的人脸区域 */
    public static Rect getLastFaceRect() { return lastFaceRect; }

    /**
     * 从 ImageProxy (相机帧) 提取特征
     */
    public static FeatureVector extractFromFrame(ImageProxy image, @Nullable Rect personBounds) {
        Bitmap bitmap = imageProxyToBitmap(image);
        if (bitmap == null) return emptyVector();

        try {
            return extractFromBitmap(bitmap, personBounds);
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * 从 Bitmap 提取特征
     */
    public static FeatureVector extractFromBitmap(Bitmap bitmap, @Nullable Rect personBounds) {
        FeatureVector fv = new FeatureVector();

        if (bitmap == null || bitmap.getWidth() < 10 || bitmap.getHeight() < 10) {
            return fv;
        }

        // 缩放到统一处理尺寸 (降低计算量)
        int targetW = Math.min(bitmap.getWidth(), 240);
        int targetH = (int) (targetW * (float) bitmap.getHeight() / bitmap.getWidth());
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);

        try {
            FeatureVector extracted = extractInternal(scaled, personBounds);
            // 记录检测区域（原始 bitmap 坐标）
            lastPersonRect = lastDetectedRoi;
            lastFaceRect = lastDetectedFace;
            return extracted;
        } finally {
            if (scaled != bitmap) scaled.recycle();
        }
    }

    // 临时存储最近一次检测的区域（scaled 坐标系）
    private static Rect lastDetectedRoi = null;
    private static Rect lastDetectedFace = null;

    /**
     * 内部提取流程
     */
    private static FeatureVector extractInternal(Bitmap bitmap, @Nullable Rect personBounds) {
        FeatureVector fv = new FeatureVector();
        int imgW = bitmap.getWidth();
        int imgH = bitmap.getHeight();

        // 1. 确定 ROI：优先用传入的检测框 → 运动检测 → 显著区域 → 默认
        Rect roi;
        if (personBounds != null) {
            roi = sanitizeRegion(personBounds, imgW, imgH);
        } else {
            roi = detectPersonROI(bitmap);
        }

        if (roi.width() < 20 || roi.height() < 40) {
            // 退化到默认区域
            roi = new Rect(
                Math.round(imgW * DEFAULT_ROI_LEFT), Math.round(imgH * DEFAULT_ROI_TOP),
                Math.round(imgW * DEFAULT_ROI_RIGHT), Math.round(imgH * DEFAULT_ROI_BOTTOM)
            );
        }

        // 2. 肤色检测 — 定位人脸位置
        Rect faceRegion = detectFaceRegion(bitmap, roi);
        lastDetectedRoi = roi;
        lastDetectedFace = faceRegion;
        Rect upperBody, lowerBody;
        if (faceRegion != null) {
            // 基于人脸位置推算身体区域
            int faceCenterY = faceRegion.centerY();
            int faceHeight = faceRegion.height();
            upperBody = new Rect(roi.left, faceCenterY + faceHeight / 2,
                                 roi.right, faceCenterY + faceHeight * 4);
            lowerBody = new Rect(roi.left, faceCenterY + faceHeight * 4,
                                 roi.right, roi.bottom);
        } else {
            int splitY = roi.top + (int) (roi.height() * 0.45f);
            upperBody = new Rect(roi.left, roi.top, roi.right, splitY);
            lowerBody = new Rect(roi.left, splitY, roi.right, roi.bottom);
        }
        upperBody = sanitizeRegion(upperBody, imgW, imgH);
        lowerBody = sanitizeRegion(lowerBody, imgW, imgH);

        // 3. 多区域颜色直方图 (上半身 + 下半身 + 头部)
        float[] colorHist = extractMultiRegionColorHistogram(bitmap, upperBody, lowerBody, faceRegion);
        fv.setColorHistogram(colorHist);

        // 4. LBP 纹理直方图
        float[] textureHist = extractLBPHistogram(bitmap, upperBody);
        fv.setTextureHistogram(textureHist);

        // 5. 边缘方向直方图 (EOH)
        float[] eohHist = extractEdgeOrientationHistogram(bitmap, roi);
        fv.setEdgeOrientation(eohHist);

        // 6. 人体比例（基于肤色检测改进）
        float[] bodyRatios = extractBodyRatiosV2(roi, imgW, imgH, faceRegion);
        fv.setBodyRatios(bodyRatios[0], bodyRatios[1], bodyRatios[2], bodyRatios[3],
                          bodyRatios[4], bodyRatios[5], bodyRatios[6], bodyRatios[7]);

        // 7. 姿态估计（基于肤色 + 轮廓）
        float[] poseKeypoints = estimatePoseV2(bitmap, roi, faceRegion);
        fv.setPoseKeypoints(poseKeypoints);

        // 8. 辅助特征
        float[] auxFeatures = extractAuxiliaryFeaturesV2(bitmap, roi, colorHist, faceRegion);
        fv.setAuxiliaryFeatures(auxFeatures[0], auxFeatures[1], auxFeatures[2], auxFeatures[3]);

        return fv;
    }

    // ==================== 运动检测 (帧间差分) ====================

    /**
     * 通过帧间差分检测运动区域，定位画面中的人物
     */
    private static Rect detectPersonROI(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        // 默认 ROI 作为基础
        Rect defaultRoi = new Rect(
            Math.round(w * DEFAULT_ROI_LEFT), Math.round(h * DEFAULT_ROI_TOP),
            Math.round(w * DEFAULT_ROI_RIGHT), Math.round(h * DEFAULT_ROI_BOTTOM)
        );

        if (prevFrame == null || prevFrameWidth != w || prevFrameHeight != h) {
            // 第一帧或尺寸变化，保存当前帧并使用默认 ROI
            cacheFrame(bitmap);
            Rect saliency = findSalientRegion(bitmap, defaultRoi);
            return saliency != null ? saliency : defaultRoi;
        }

        // 计算帧差
        Rect motionRoi = computeMotionROI(bitmap, prevFrame, defaultRoi);

        // 更新缓存
        cacheFrame(bitmap);

        return motionRoi != null ? motionRoi : defaultRoi;
    }

    private static void cacheFrame(Bitmap bitmap) {
        if (prevFrame != null && prevFrame != bitmap) {
            prevFrame.recycle();
        }
        prevFrame = Bitmap.createBitmap(bitmap);
        prevFrameWidth = bitmap.getWidth();
        prevFrameHeight = bitmap.getHeight();
    }

    /**
     * 帧间差分 → 运动区域
     */
    private static Rect computeMotionROI(Bitmap curr, Bitmap prev, Rect searchArea) {
        int step = Math.max(3, Math.min(searchArea.width(), searchArea.height()) / 30);
        int gridSize = step * 2;
        int cols = searchArea.width() / gridSize;
        int rows = searchArea.height() / gridSize;

        if (cols < 2 || rows < 2) return null;

        // 每个网格计算平均亮度差
        float[][] diffMap = new float[rows][cols];
        float maxDiff = 0;
        float avgDiff = 0;
        int count = 0;

        for (int gr = 0; gr < rows; gr++) {
            for (int gc = 0; gc < cols; gc++) {
                int x0 = searchArea.left + gc * gridSize;
                int y0 = searchArea.top + gr * gridSize;
                float d = 0;
                int n = 0;
                for (int dy = 0; dy < gridSize && y0 + dy < curr.getHeight(); dy += step) {
                    for (int dx = 0; dx < gridSize && x0 + dx < curr.getWidth(); dx += step) {
                        int cPixel = curr.getPixel(x0 + dx, y0 + dy);
                        int pPixel = prev.getPixel(x0 + dx, y0 + dy);
                        int cGray = (Color.red(cPixel) + Color.green(cPixel) + Color.blue(cPixel)) / 3;
                        int pGray = (Color.red(pPixel) + Color.green(pPixel) + Color.blue(pPixel)) / 3;
                        d += Math.abs(cGray - pGray);
                        n++;
                    }
                }
                d /= Math.max(n, 1);
                diffMap[gr][gc] = d;
                maxDiff = Math.max(maxDiff, d);
                avgDiff += d;
                count++;
            }
        }
        avgDiff /= Math.max(count, 1);

        // 动态阈值
        float threshold = avgDiff + (maxDiff - avgDiff) * 0.35f;
        if (threshold < 8) return null; // 无明显运动

        // 找运动区域的包围框
        int minCol = cols, minRow = rows, maxCol = -1, maxRow = -1;
        for (int gr = 0; gr < rows; gr++) {
            for (int gc = 0; gc < cols; gc++) {
                if (diffMap[gr][gc] > threshold) {
                    minCol = Math.min(minCol, gc);
                    minRow = Math.min(minRow, gr);
                    maxCol = Math.max(maxCol, gc);
                    maxRow = Math.max(maxRow, gr);
                }
            }
        }

        if (maxCol < 0) return null;

        // 扩展边界，加入余量
        int padCol = Math.max(1, (maxCol - minCol) / 4);
        int padRow = Math.max(1, (maxRow - minRow) / 4);
        minCol = Math.max(0, minCol - padCol);
        minRow = Math.max(0, minRow - padRow);
        maxCol = Math.min(cols - 1, maxCol + padCol);
        maxRow = Math.min(rows - 1, maxRow + padRow);

        int left = searchArea.left + minCol * gridSize;
        int top = searchArea.top + minRow * gridSize;
        int right = searchArea.left + (maxCol + 1) * gridSize;
        int bottom = searchArea.top + (maxRow + 1) * gridSize;

        // 确保最小尺寸
        int minW = (int) (searchArea.width() * 0.3f);
        int minH = (int) (searchArea.height() * 0.3f);
        if (right - left < minW) {
            int cx = (left + right) / 2;
            left = cx - minW / 2;
            right = cx + minW / 2;
        }
        if (bottom - top < minH) {
            int cy = (top + bottom) / 2;
            top = cy - minH / 2;
            bottom = cy + minH / 2;
        }

        return sanitizeRegion(new Rect(left, top, right, bottom), curr.getWidth(), curr.getHeight());
    }

    // ==================== 肤色检测 ====================

    /**
     * 检测画面中的脸部区域
     * 使用 YCrCb 色彩空间进行肤色检测
     */
    private static Rect detectFaceRegion(Bitmap bitmap, Rect searchArea) {
        int step = Math.max(2, searchArea.width() / 50);
        int minX = searchArea.right, minY = searchArea.bottom;
        int maxX = searchArea.left, maxY = searchArea.top;
        int hits = 0;

        // 只搜索 ROI 上半部分（脸部通常在上方）
        int searchBottom = searchArea.top + searchArea.height() * 2 / 5;

        for (int y = searchArea.top; y < searchBottom; y += step) {
            for (int x = searchArea.left; x < searchArea.right; x += step) {
                if (x >= bitmap.getWidth() || y >= bitmap.getHeight()) continue;
                int pixel = bitmap.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                // YCrCb 肤色检测
                int cr = (int) (0.5f * r - 0.419f * g - 0.081f * b) + 128;
                int cb = (int) (-0.169f * r - 0.331f * g + 0.5f * b) + 128;

                if (cr >= CR_MIN && cr <= CR_MAX && cb >= CB_MIN && cb <= CB_MAX) {
                    // 额外检查：亮度不能太低或太高
                    int y_luma = (int) (0.299f * r + 0.587f * g + 0.114f * b);
                    if (y_luma > 50 && y_luma < 240) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                        hits++;
                    }
                }
            }
        }

        // 最少需要足够的肤色像素
        int minHits = Math.max(8, (searchArea.width() / step) * (searchArea.height() / step) / 100);
        if (hits < minHits || maxX <= minX || maxY <= minY) {
            return null;
        }

        // 脸部应该是近似正方形的区域
        int faceW = maxX - minX;
        int faceH = maxY - minY;
        float aspect = (float) faceW / Math.max(faceH, 1);
        if (aspect < 0.5f || aspect > 2.5f) return null;

        return new Rect(minX, minY, maxX, maxY);
    }

    // ==================== 多区域颜色直方图 ====================

    /**
     * 三区域 HSV 颜色直方图：上半身(50%) + 下半身(35%) + 头部(15%)
     * 每区域 16 H-bins + 16 tone-bins = 32 bins
     * 总计 48 dims（与 v2.0 兼容）
     */
    private static float[] extractMultiRegionColorHistogram(Bitmap bitmap, Rect upperBody, Rect lowerBody, @Nullable Rect faceRegion) {
        float[] hist = new float[48];

        float[] upperHist = computeHSVHistogram(bitmap, upperBody, H_BINS);
        float[] lowerHist = computeHSVHistogram(bitmap, lowerBody, H_BINS);

        // 如果有脸部区域，提取头发颜色
        float[] hairColor = null;
        if (faceRegion != null) {
            Rect hairRegion = new Rect(
                faceRegion.left, Math.max(0, faceRegion.top - faceRegion.height() / 2),
                faceRegion.right, faceRegion.top + faceRegion.height() / 4
            );
            hairRegion = sanitizeRegion(hairRegion, bitmap.getWidth(), bitmap.getHeight());
            hairColor = computeHSVHistogram(bitmap, hairRegion, H_BINS);
        }

        // 上半身 50%
        for (int i = 0; i < 16; i++) {
            hist[i] = upperHist[i] * 0.50f;
            hist[i + 32] = upperHist[i + 16] * 0.50f;
        }
        // 下半身 35%
        for (int i = 0; i < 16; i++) {
            hist[i] += lowerHist[i] * 0.35f;
            hist[i + 32] += lowerHist[i + 16] * 0.35f;
        }
        // 头部/头发 15%
        if (hairColor != null) {
            for (int i = 0; i < 16; i++) {
                hist[i] += hairColor[i] * 0.15f;
                hist[i + 32] += hairColor[i + 16] * 0.15f;
            }
        } else {
            // 无头发信息，分配给上半身
            for (int i = 0; i < 16; i++) {
                hist[i] += upperHist[i] * 0.15f;
                hist[i + 32] += upperHist[i + 16] * 0.15f;
            }
        }

        // L1 归一化
        normalize(hist);
        return hist;
    }

    /**
     * 计算 HSV 直方图 (H: 16 bins + tone: 16 bins)
     */
    private static float[] computeHSVHistogram(Bitmap bitmap, Rect region, int bins) {
        float[] hist = new float[32];
        int step = Math.max(1, Math.min(region.width(), region.height()) / 40);
        int count = 0;

        for (int py = region.top; py < region.bottom; py += step) {
            for (int px = region.left; px < region.right; px += step) {
                if (px >= bitmap.getWidth() || py >= bitmap.getHeight()) continue;

                int pixel = bitmap.getPixel(px, py);
                float[] hsv = new float[3];
                Color.colorToHSV(pixel, hsv);

                int toneBin = Math.min((int) (hsv[2] * bins), bins - 1);
                float colorWeight = hsv[1];
                if (hsv[1] >= 0.18f) {
                    int hBin = (int) (hsv[0] / 360f * bins) % bins;
                    hist[hBin] += colorWeight;
                }
                hist[16 + toneBin] += 1f - colorWeight * 0.45f;
                count++;
            }
        }

        if (count > 0) {
            for (int i = 0; i < 16; i++) hist[i] /= count;
            for (int i = 16; i < 32; i++) hist[i] /= count;
        }

        return hist;
    }

    // ==================== LBP 纹理直方图 ====================

    /**
     * 计算 Local Binary Pattern 纹理直方图
     * 捕捉衣着纹理模式（纯色/条纹/格子/花纹等）
     */
    private static float[] extractLBPHistogram(Bitmap bitmap, Rect region) {
        float[] hist = new float[LBP_BINS];
        int step = Math.max(1, Math.min(region.width(), region.height()) / 50);
        int count = 0;

        // 需要 3x3 邻域，所以从 (1,1) 开始
        for (int y = Math.max(region.top + 1, 1); y < Math.min(region.bottom, bitmap.getHeight() - 1); y += step) {
            for (int x = Math.max(region.left + 1, 1); x < Math.min(region.right, bitmap.getWidth() - 1); x += step) {
                int center = luminance(bitmap.getPixel(x, y));
                int pattern = 0;
                if (luminance(bitmap.getPixel(x - 1, y - 1)) >= center) pattern |= 1;
                if (luminance(bitmap.getPixel(x, y - 1)) >= center) pattern |= 2;
                if (luminance(bitmap.getPixel(x + 1, y - 1)) >= center) pattern |= 4;
                if (luminance(bitmap.getPixel(x + 1, y)) >= center) pattern |= 8;
                if (luminance(bitmap.getPixel(x + 1, y + 1)) >= center) pattern |= 16;
                if (luminance(bitmap.getPixel(x, y + 1)) >= center) pattern |= 32;
                if (luminance(bitmap.getPixel(x - 1, y + 1)) >= center) pattern |= 64;
                if (luminance(bitmap.getPixel(x - 1, y)) >= center) pattern |= 128;

                // 统一 LBP (uniform LBP)：跳过 transition count > 2 的 pattern
                int transitions = countTransitions(pattern);
                if (transitions <= 2) {
                    int bin = uniformLbpBin(pattern, LBP_BINS);
                    hist[bin]++;
                } else {
                    hist[LBP_BINS - 1]++; // 非统一模式归入最后一 bin
                }
                count++;
            }
        }

        if (count > 0) {
            for (int i = 0; i < LBP_BINS; i++) hist[i] /= count;
        }

        return hist;
    }

    private static int luminance(int pixel) {
        return (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000;
    }

    private static int countTransitions(int pattern) {
        int count = 0;
        boolean prev = (pattern & 128) != 0;
        for (int i = 1; i <= 8; i++) {
            boolean bit = (pattern & (1 << (i % 8))) != 0;
            if (bit != prev) count++;
            prev = bit;
        }
        return count;
    }

    private static int uniformLbpBin(int pattern, int bins) {
        // 统一 LBP 的 bin 编号：按 1 的个数分配
        int ones = Integer.bitCount(pattern);
        return Math.min(ones, bins - 2);
    }

    // ==================== 边缘方向直方图 ====================

    /**
     * 计算边缘方向直方图 (Edge Orientation Histogram)
     * 从 Sobel 梯度方向捕捉人体姿态轮廓
     */
    private static float[] extractEdgeOrientationHistogram(Bitmap bitmap, Rect region) {
        float[] hist = new float[EOH_BINS];
        int step = Math.max(1, Math.min(region.width(), region.height()) / 40);
        int count = 0;

        for (int y = Math.max(region.top + 1, 1); y < Math.min(region.bottom, bitmap.getHeight() - 1); y += step) {
            for (int x = Math.max(region.left + 1, 1); x < Math.min(region.right, bitmap.getWidth() - 1); x += step) {
                int gx = luminance(bitmap.getPixel(x + 1, y - 1)) + 2 * luminance(bitmap.getPixel(x + 1, y)) + luminance(bitmap.getPixel(x + 1, y + 1))
                       - luminance(bitmap.getPixel(x - 1, y - 1)) - 2 * luminance(bitmap.getPixel(x - 1, y)) - luminance(bitmap.getPixel(x - 1, y + 1));
                int gy = luminance(bitmap.getPixel(x - 1, y + 1)) + 2 * luminance(bitmap.getPixel(x, y + 1)) + luminance(bitmap.getPixel(x + 1, y + 1))
                       - luminance(bitmap.getPixel(x - 1, y - 1)) - 2 * luminance(bitmap.getPixel(x, y - 1)) - luminance(bitmap.getPixel(x + 1, y - 1));

                int mag = Math.abs(gx) + Math.abs(gy);
                if (mag < 20) continue; // 跳过弱边缘

                float angle = (float) Math.atan2(gy, gx);
                // 映射 [-π, π] → [0, EOH_BINS)
                int bin = (int) (((angle + Math.PI) / (2 * Math.PI)) * EOH_BINS);
                bin = Math.max(0, Math.min(EOH_BINS - 1, bin));
                hist[bin] += mag;
                count++;
            }
        }

        if (count > 0) {
            normalize(hist);
        }

        return hist;
    }

    // ==================== 人体比例 v2 (肤色增强) ====================

    /**
     * 基于肤色检测的人体比例估计
     */
    private static float[] extractBodyRatiosV2(Rect bounds, int imgW, int imgH, @Nullable Rect faceRegion) {
        float[] ratios = new float[8];

        float boxW = (float) bounds.width() / imgW;
        float boxH = (float) bounds.height() / imgH;
        float aspectRatio = boxW / Math.max(boxH, 0.01f);

        // 肩宽比
        ratios[0] = clamp(aspectRatio * 0.7f, 0.15f, 0.5f);

        // 腰臀比
        ratios[1] = 0.75f + (aspectRatio - 0.3f) * 0.3f;
        ratios[1] = clamp(ratios[1], 0.6f, 1.0f);

        // 身高比例
        ratios[2] = clamp(boxH, 0.3f, 1.0f);

        // 腿身比
        ratios[3] = 0.48f + (boxH - 0.5f) * 0.1f;
        ratios[3] = clamp(ratios[3], 0.4f, 0.6f);

        // 臂展比
        ratios[4] = 0.95f + (aspectRatio - 0.3f) * 0.2f;
        ratios[4] = clamp(ratios[4], 0.8f, 1.2f);

        // 头身比：如果有脸部信息，用真实数据
        if (faceRegion != null && bounds.height() > 0) {
            float faceHeightRatio = (float) faceRegion.height() / bounds.height();
            // 头身比 = 身高 / 头高，典型值 6~8
            float headBodyRatio = 1f / Math.max(faceHeightRatio * 7f, 0.1f);
            ratios[5] = clamp(headBodyRatio, 0.5f, 1.0f);
        } else {
            ratios[5] = clamp(7f / (boxH * 10f), 0.5f, 1.0f);
        }

        // 肩腰比
        ratios[6] = ratios[0] * 1.2f;
        ratios[6] = clamp(ratios[6], 0.5f, 1.0f);

        // 胸腰比
        ratios[7] = ratios[6] * 0.95f;
        ratios[7] = clamp(ratios[7], 0.5f, 1.0f);

        return ratios;
    }

    // ==================== 姿态估计 v2 ====================

    /**
     * 基于肤色 + 边缘的姿态关键点估计
     */
    private static float[] estimatePoseV2(Bitmap bitmap, Rect bounds, @Nullable Rect faceRegion) {
        float[] keypoints = new float[12];

        float cx, cy, bw, bh;
        if (faceRegion != null) {
            cx = (faceRegion.left + faceRegion.right) / 2f / bitmap.getWidth();
            cy = (faceRegion.top + faceRegion.bottom) / 2f / bitmap.getHeight();
            bw = (float) bounds.width() / bitmap.getWidth();
            bh = (float) bounds.height() / bitmap.getHeight();

            // 鼻子：脸部中心
            keypoints[0] = cx;
            keypoints[1] = cy;
        } else {
            cx = (bounds.left + bounds.right) / 2f / bitmap.getWidth();
            cy = (bounds.top + bounds.bottom) / 2f / bitmap.getHeight();
            bw = (float) bounds.width() / bitmap.getWidth();
            bh = (float) bounds.height() / bitmap.getHeight();

            keypoints[0] = cx;
            keypoints[1] = (float) bounds.top / bitmap.getHeight() + bh * 0.12f;
        }

        float faceHeight = faceRegion != null ? (float) faceRegion.height() / bitmap.getHeight() : bh * 0.12f;
        float shoulderY = (float) bounds.top / bitmap.getHeight() + faceHeight * 2.2f;
        float shoulderSpread = bw * 0.42f;

        keypoints[2] = cx - shoulderSpread; // 左肩
        keypoints[3] = shoulderY;
        keypoints[4] = cx + shoulderSpread; // 右肩
        keypoints[5] = shoulderY;

        float hipY = (float) bounds.top / bitmap.getHeight() + bh * 0.55f;
        float hipSpread = bw * 0.22f;
        keypoints[6] = cx - hipSpread; // 左髋
        keypoints[7] = hipY;
        keypoints[8] = cx + hipSpread; // 右髋
        keypoints[9] = hipY;

        keypoints[10] = cx - hipSpread * 0.8f; // 左膝
        keypoints[11] = (float) bounds.top / bitmap.getHeight() + bh * 0.72f;

        return keypoints;
    }

    // ==================== 辅助特征 v2 ====================

    private static float[] extractAuxiliaryFeaturesV2(Bitmap bitmap, Rect bounds, float[] colorHist, @Nullable Rect faceRegion) {
        float[] aux = new float[4];

        // 体型: 从宽高比推算
        float aspect = (float) bounds.width() / Math.max(bounds.height(), 1);
        aux[0] = clamp((aspect - 0.2f) / 0.4f, 0f, 1f);

        // 性别: 颜色丰富度 + 肩宽比 + 脸部信息
        float colorDiversity = 0;
        for (float v : colorHist) colorDiversity += v * v;
        colorDiversity = 1f - colorDiversity;

        // 肩宽比：男性通常更宽
        float shoulderEstimate = aspect;
        float maleLikelihood = clamp((shoulderEstimate - 0.25f) / 0.2f, 0f, 1f);

        aux[1] = clamp(colorDiversity * 1.5f + (1f - maleLikelihood) * 0.4f, 0f, 1f);

        // 年龄: 默认中年
        aux[2] = 0.35f;

        // 姿态角度: 从边缘直方图推算（简化）
        aux[3] = 0f;

        return aux;
    }

    // ==================== 文本 → 特征 ====================

    /**
     * 从 PersonTarget 的文本属性生成特征向量
     */
    public static FeatureVector extractFromTarget(PersonTarget target) {
        FeatureVector fv = new FeatureVector();

        float[] colorHist = textToColorHistogram(target.clothing, target.accessories);
        fv.setColorHistogram(colorHist);

        float[] bodyRatios = textToBodyRatios(target.bodyShape, target.gender);
        fv.setBodyRatios(bodyRatios[0], bodyRatios[1], bodyRatios[2], bodyRatios[3],
                          bodyRatios[4], bodyRatios[5], bodyRatios[6], bodyRatios[7]);

        float[] pose = new float[12];
        pose[0] = 0.5f; pose[1] = 0.15f;
        pose[2] = 0.38f; pose[3] = 0.35f;
        pose[4] = 0.62f; pose[5] = 0.35f;
        pose[6] = 0.42f; pose[7] = 0.55f;
        pose[8] = 0.58f; pose[9] = 0.55f;
        pose[10] = 0.42f; pose[11] = 0.75f;
        fv.setPoseKeypoints(pose);

        float genderProb = "女".equals(target.gender) ? 0.85f : "男".equals(target.gender) ? 0.15f : 0.5f;
        float bodyType = textToBodyType(target.bodyShape);
        fv.setAuxiliaryFeatures(bodyType, genderProb, 0.3f, 0f);

        // 纹理：从关键词推算
        float[] texture = textToTexture(target.clothing, target.accessories);
        fv.setTextureHistogram(texture);

        // 边缘方向：默认均匀分布
        float[] eoh = new float[EOH_BINS];
        for (int i = 0; i < EOH_BINS; i++) eoh[i] = 1f / EOH_BINS;
        fv.setEdgeOrientation(eoh);

        return fv;
    }

    private static float[] textToTexture(String clothing, String accessories) {
        float[] hist = new float[LBP_BINS];
        String combined = safeLower(clothing) + " " + safeLower(accessories);

        // 默认：纯色 (bin 0)
        hist[0] = 0.6f;

        if (containsAny(combined, "条纹", "stripe")) {
            hist[0] = 0.2f;
            hist[1] = 0.3f; hist[2] = 0.3f; // 有方向性
        }
        if (containsAny(combined, "格子", "plaid", "check")) {
            hist[0] = 0.15f;
            for (int i = 1; i < 5; i++) hist[i] = 0.15f; // 多方向
        }
        if (containsAny(combined, "花纹", "印花", "pattern", "print", "图案")) {
            hist[LBP_BINS - 1] = 0.5f; // 非统一模式
        }
        if (containsAny(combined, "牛仔", "denim", "jeans")) {
            hist[0] = 0.3f;
            hist[LBP_BINS - 1] = 0.4f; // 粗糙纹理
        }
        if (containsAny(combined, "针织", "毛衣", "毛", "wool", "knit")) {
            hist[0] = 0.35f;
            hist[1] = 0.25f;
        }
        if (containsAny(combined, "皮", "leather")) {
            hist[0] = 0.5f;
            hist[LBP_BINS - 1] = 0.25f;
        }

        normalize(hist);
        return hist;
    }

    // ==================== 文本 → 颜色直方图 ====================

    private static float[] textToColorHistogram(String clothing, String accessories) {
        float[] hist = new float[48];
        String combined = safeLower(clothing) + " " + safeLower(accessories);

        // 主色调映射
        if (containsAny(combined, "红", "red", "大红", "玫红")) addHue(hist, 0, 0.8f);
        if (containsAny(combined, "橙红", "橘红")) { addHue(hist, 0, 0.4f); addHue(hist, 1, 0.4f); }
        if (containsAny(combined, "橙", "橘", "orange")) addHue(hist, 2, 0.7f);
        if (containsAny(combined, "黄", "yellow", "金色", "金黄")) addHue(hist, 4, 0.7f);
        if (containsAny(combined, "黄绿", "嫩绿")) addHue(hist, 5, 0.6f);
        if (containsAny(combined, "绿", "green", "墨绿", "军绿")) addHue(hist, 6, 0.7f);
        if (containsAny(combined, "青绿", "翠绿")) addHue(hist, 7, 0.6f);
        if (containsAny(combined, "青", "cyan", "蓝绿", "湖蓝")) addHue(hist, 8, 0.6f);
        if (containsAny(combined, "浅蓝", "天蓝", "淡蓝")) addHue(hist, 9, 0.7f);
        if (containsAny(combined, "蓝", "blue", "深蓝", "藏青", "藏蓝")) addHue(hist, 10, 0.8f);
        if (containsAny(combined, "宝蓝", "靛蓝")) { addHue(hist, 10, 0.5f); addHue(hist, 11, 0.4f); }
        if (containsAny(combined, "紫", "purple", "紫红")) addHue(hist, 12, 0.7f);
        if (containsAny(combined, "粉", "pink", "粉红")) addHue(hist, 14, 0.7f);
        if (containsAny(combined, "玫瑰", "rose")) addHue(hist, 15, 0.7f);
        if (containsAny(combined, "棕", "brown", "咖啡", "褐色", "土黄")) addHue(hist, 1, 0.6f);
        if (containsAny(combined, "米", "beige", "卡其", "驼色")) addHue(hist, 3, 0.5f);
        if (containsAny(combined, "卡其绿", "军绿")) { addHue(hist, 1, 0.3f); addHue(hist, 6, 0.4f); }

        // 无彩色
        if (containsAny(combined, "黑", "black")) addTone(hist, 1, 1.0f);
        if (containsAny(combined, "深灰", "炭灰")) addTone(hist, 4, 0.8f);
        if (containsAny(combined, "灰", "gray", "grey")) addTone(hist, 7, 0.8f);
        if (containsAny(combined, "浅灰", "银灰")) addTone(hist, 11, 0.7f);
        if (containsAny(combined, "白", "white", "米白")) addTone(hist, 14, 1.0f);
        if (containsAny(combined, "杏色", "肤色")) addTone(hist, 12, 0.5f);

        // 上下身分别处理
        String upper = safeLower(clothing);
        String lower = safeLower(accessories);
        if (containsAny(upper, "裙", "裤", "短裙", "长裙")) {
            // clothing 可能包含下半身描述
        }

        float sum = 0;
        for (float v : hist) sum += v;
        if (sum < 0.1f) {
            for (int i = 0; i < 48; i++) hist[i] = 1f / 48f;
        } else {
            normalize(hist);
        }

        return hist;
    }

    // ==================== 文本 → 人体比例 ====================

    private static float[] textToBodyRatios(String bodyShape, String gender) {
        float[] ratios = new float[8];
        ratios[0] = 0.3f;  ratios[1] = 0.75f; ratios[2] = 0.7f;
        ratios[3] = 0.48f; ratios[4] = 0.95f; ratios[5] = 0.7f;
        ratios[6] = 0.8f;  ratios[7] = 0.76f;

        String bs = safeLower(bodyShape) + " " + safeLower(gender);
        if (containsAny(bs, "高", "tall")) ratios[2] = 0.9f;
        if (containsAny(bs, "矮", "short")) ratios[2] = 0.5f;
        if (containsAny(bs, "瘦", "slim", "苗条", "纤细")) {
            ratios[0] = 0.25f; ratios[1] = 0.7f; ratios[6] = 0.7f;
        }
        if (containsAny(bs, "胖", "fat", "壮", "微胖", "丰满")) {
            ratios[0] = 0.38f; ratios[1] = 0.85f; ratios[6] = 0.9f;
        }
        if (containsAny(bs, "女")) { ratios[0] = 0.26f; ratios[1] = 0.7f; ratios[6] = 0.72f; }
        if (containsAny(bs, "男")) { ratios[0] = 0.36f; ratios[1] = 0.85f; ratios[6] = 0.88f; }
        return ratios;
    }

    private static float textToBodyType(String bodyShape) {
        if (bodyShape == null) return 0.5f;
        String bs = safeLower(bodyShape);
        if (containsAny(bs, "瘦", "苗条", "纤细", "slim")) return 0.2f;
        if (containsAny(bs, "壮", "胖", "丰满", "微胖")) return 0.8f;
        return 0.5f;
    }

    // ==================== 工具方法 ====================

    private static Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            byte[] nv21 = imageProxyToNv21(image);
            if (nv21 == null) return null;
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21,
                image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 80, out);
            byte[] jpegBytes = out.toByteArray();
            return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] imageProxyToNv21(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return null;

        int width = image.getWidth();
        int height = image.getHeight();
        byte[] out = new byte[width * height * 3 / 2];
        int offset = 0;

        copyPlane(planes[0], width, height, out, offset, 1);
        offset += width * height;

        ByteBuffer vBuffer = planes[2].getBuffer().duplicate();
        ByteBuffer uBuffer = planes[1].getBuffer().duplicate();
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        int vRowStride = planes[2].getRowStride();
        int uRowStride = planes[1].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int uPixelStride = planes[1].getPixelStride();

        for (int row = 0; row < chromaHeight; row++) {
            for (int col = 0; col < chromaWidth; col++) {
                int vIndex = row * vRowStride + col * vPixelStride;
                int uIndex = row * uRowStride + col * uPixelStride;
                out[offset++] = vBuffer.get(vIndex);
                out[offset++] = uBuffer.get(uIndex);
            }
        }
        return out;
    }

    private static void copyPlane(ImageProxy.PlaneProxy plane, int width, int height, byte[] out, int offset, int pixelStrideOut) {
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int outOffset = offset;
        for (int row = 0; row < height; row++) {
            int rowStart = row * rowStride;
            for (int col = 0; col < width; col++) {
                out[outOffset] = buffer.get(rowStart + col * pixelStride);
                outOffset += pixelStrideOut;
            }
        }
    }

    private static Rect findSalientRegion(Bitmap bitmap, Rect base) {
        int step = Math.max(4, Math.min(base.width(), base.height()) / 36);
        int minX = base.right, minY = base.bottom;
        int maxX = base.left, maxY = base.top;
        int hits = 0;
        float[] hsv = new float[3];

        for (int y = base.top; y < base.bottom; y += step) {
            for (int x = base.left; x < base.right; x += step) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv);
                boolean usefulColor = hsv[1] > 0.16f && hsv[2] > 0.12f && hsv[2] < 0.92f;
                boolean usefulTone = hsv[1] <= 0.16f && hsv[2] > 0.08f && hsv[2] < 0.82f;
                if (usefulColor || usefulTone) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    hits++;
                }
            }
        }

        int minHits = Math.max(12, (base.width() / step) * (base.height() / step) / 20);
        if (hits < minHits || maxX <= minX || maxY <= minY) return null;

        int padX = Math.round(base.width() * 0.08f);
        int padY = Math.round(base.height() * 0.06f);
        return new Rect(minX - padX, minY - padY, maxX + padX, maxY + padY);
    }

    private static Rect sanitizeRegion(Rect region, int width, int height) {
        int left = clampInt(region.left, 0, width - 1);
        int top = clampInt(region.top, 0, height - 1);
        int right = clampInt(region.right, left + 1, width);
        int bottom = clampInt(region.bottom, top + 1, height);
        return new Rect(left, top, right, bottom);
    }

    private static void normalize(float[] arr) {
        float sum = 0;
        for (float v : arr) sum += Math.abs(v);
        if (sum > 0) {
            for (int i = 0; i < arr.length; i++) arr[i] /= sum;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void addHue(float[] hist, int bin, float weight) {
        int safeBin = clampInt(bin, 0, 15);
        hist[safeBin] += weight * 0.58f;
        hist[16 + safeBin] += weight * 0.42f;
        hist[32 + 10] += weight * 0.25f;
    }

    private static void addTone(float[] hist, int bin, float weight) {
        int safeBin = clampInt(bin, 0, 15);
        hist[32 + safeBin] += weight;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private static FeatureVector emptyVector() {
        FeatureVector fv = new FeatureVector();
        float[] uniform = new float[48];
        for (int i = 0; i < 48; i++) uniform[i] = 1f / 48f;
        fv.setColorHistogram(uniform);
        return fv;
    }
}
