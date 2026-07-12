#!/usr/bin/env python3
"""
图片特征提取器 — Python 版（与 Java FeatureExtractor 算法一致）

提取 128 维特征向量:
- [0..47]   颜色直方图: HSV (上半身 50% + 下半身 35% + 头部 15%)
- [48..63]  LBP 纹理直方图: 16 bins
- [64..72]  边缘方向直方图: 9 bins
- [73..80]  人体比例: 8 个比例
- [81..92]  姿态关键点: 6 个关键点 × 2
- [93..96]  辅助特征: 体型/性别/年龄/姿态角度
- [97..127] 填充

使用方式:
    python3 extract_features.py image.jpg
    python3 extract_features.py image.jpg --save output.fv.json
    python3 extract_features.py image.jpg --target-name "张三"
"""

import sys
import os
import json
import argparse
import math
import io
import re
from collections import defaultdict

try:
    from PIL import Image
    import numpy as np
except ImportError:
    print("需要: pip3 install pillow numpy", file=sys.stderr)
    sys.exit(1)

# HSV 参数（与 Java 一致）
H_BINS = 16
LBP_BINS = 16
EOH_BINS = 9
DIM = 128

# YCrCb 肤色范围
CR_MIN, CR_MAX = 133, 173
CB_MIN, CB_MAX = 77, 127


# ==================== 工具函数 ====================

def rgb_to_hsv(r, g, b):
    """RGB → HSV (与 Android Color.colorToHSV 一致)"""
    rf, gf, bf = r / 255.0, g / 255.0, b / 255.0
    cmax = max(rf, gf, bf)
    cmin = min(rf, gf, bf)
    delta = cmax - cmin

    if delta == 0:
        h = 0
    elif cmax == rf:
        h = (60 * ((gf - bf) / delta) + 360) % 360
    elif cmax == gf:
        h = (60 * ((bf - rf) / delta) + 120) % 360
    else:
        h = (60 * ((rf - gf) / delta) + 240) % 360

    s = 0 if cmax == 0 else delta / cmax
    v = cmax
    return h, s, v


def rgb_to_ycrcb(r, g, b):
    """RGB → YCrCb"""
    y = 0.299 * r + 0.587 * g + 0.114 * b
    cr = (0.5 * r - 0.419 * g - 0.081 * b) + 128
    cb = (-0.169 * r - 0.331 * g + 0.5 * b) + 128
    return int(y), int(cr), int(cb)


def luminance(r, g, b):
    r, g, b = int(r) & 0xFF, int(g) & 0xFF, int(b) & 0xFF
    return (r * 299 + g * 587 + b * 114) // 1000


def sanitize_region(left, top, right, bottom, w, h):
    left = max(0, min(left, w - 1))
    top = max(0, min(top, h - 1))
    right = max(left + 1, min(right, w))
    bottom = max(top + 1, min(bottom, h))
    return left, top, right, bottom


# ==================== 特征提取（与 Java 对齐） ====================

def compute_hsv_histogram(arr, left, top, right, bottom, bins=16):
    """计算 HSV 直方图（H: 16 bins + tone: 16 bins = 32 bins）"""
    h_region = np.zeros(bins, dtype=np.float32)
    t_region = np.zeros(bins, dtype=np.float32)
    count = 0

    h, w = arr.shape[:2]
    step = max(1, min(right - left, bottom - top) // 40)

    for y in range(top, bottom, step):
        if y >= h: break
        for x in range(left, right, step):
            if x >= w: break
            r, g, b = arr[y, x, 0], arr[y, x, 1], arr[y, x, 2]
            hv, sv, vv = rgb_to_hsv(int(r), int(g), int(b))

            tone_bin = min(int(vv * bins), bins - 1)
            color_weight = sv
            if sv >= 0.18:
                h_bin = int(hv / 360.0 * bins) % bins
                h_region[h_bin] += color_weight
            t_region[tone_bin] += 1.0 - color_weight * 0.45
            count += 1

    if count > 0:
        h_region /= count
        t_region /= count

    return np.concatenate([h_region, t_region])


def detect_face_region(arr, search_left, search_top, search_right, search_bottom):
    """肤色检测 — 定位人脸区域"""
    step = max(2, (search_right - search_left) // 50)
    h, w = arr.shape[:2]

    # 只搜索上半部分
    search_bot = search_top + (search_bottom - search_top) * 2 // 5

    min_x, min_y = search_right, search_bot
    max_x, max_y = search_left, search_top
    hits = 0

    for y in range(search_top, min(search_bot, h), step):
        for x in range(search_left, min(search_right, w), step):
            r, g, b = arr[y, x, 0], arr[y, x, 1], arr[y, x, 2]
            _, cr, cb = rgb_to_ycrcb(int(r), int(g), int(b))
            y_lum, _, _ = rgb_to_ycrcb(int(r), int(g), int(b))

            if CR_MIN <= cr <= CR_MAX and CB_MIN <= cb <= CB_MAX:
                if 50 < y_lum < 240:
                    min_x = min(min_x, x)
                    min_y = min(min_y, y)
                    max_x = max(max_x, x)
                    max_y = max(max_y, y)
                    hits += 1

    min_hits = max(8, ((search_right - search_left) // step) * ((search_bottom - search_top) // step) // 100)
    if hits < min_hits or max_x <= min_x or max_y <= min_y:
        return None

    face_w = max_x - min_x
    face_h = max_y - min_y
    aspect = face_w / max(face_h, 1)
    if aspect < 0.5 or aspect > 2.5:
        return None

    return (min_x, min_y, max_x, max_y)


def extract_lbp_histogram(arr, left, top, right, bottom):
    """LBP 纹理直方图"""
    hist = np.zeros(LBP_BINS, dtype=np.float32)
    h, w = arr.shape[:2]
    step = max(1, min(right - left, bottom - top) // 50)
    count = 0

    for y in range(max(top + 1, 1), min(bottom, h - 1), step):
        for x in range(max(left + 1, 1), min(right, w - 1), step):
            center = luminance(arr[y, x, 0], arr[y, x, 1], arr[y, x, 2])
            pattern = 0
            if luminance(arr[y - 1, x - 1, 0], arr[y - 1, x - 1, 1], arr[y - 1, x - 1, 2]) >= center: pattern |= 1
            if luminance(arr[y - 1, x, 0], arr[y - 1, x, 1], arr[y - 1, x, 2]) >= center: pattern |= 2
            if luminance(arr[y - 1, x + 1, 0], arr[y - 1, x + 1, 1], arr[y - 1, x + 1, 2]) >= center: pattern |= 4
            if luminance(arr[y, x + 1, 0], arr[y, x + 1, 1], arr[y, x + 1, 2]) >= center: pattern |= 8
            if luminance(arr[y + 1, x + 1, 0], arr[y + 1, x + 1, 1], arr[y + 1, x + 1, 2]) >= center: pattern |= 16
            if luminance(arr[y + 1, x, 0], arr[y + 1, x, 1], arr[y + 1, x, 2]) >= center: pattern |= 32
            if luminance(arr[y + 1, x - 1, 0], arr[y + 1, x - 1, 1], arr[y + 1, x - 1, 2]) >= center: pattern |= 64
            if luminance(arr[y, x - 1, 0], arr[y, x - 1, 1], arr[y, x - 1, 2]) >= center: pattern |= 128

            # 统一 LBP
            transitions = bin(pattern ^ (pattern >> 1)).count("1") - 1
            # Java 的 countTransitions: 检测 8-bit 循环中 01 转换次数
            transitions = 0
            prev = (pattern & 128) != 0
            for i in range(1, 9):
                bit = (pattern & (1 << (i % 8))) != 0
                if bit != prev:
                    transitions += 1
                prev = bit

            if transitions <= 2:
                ones = bin(pattern).count("1")
                bin_idx = min(ones, LBP_BINS - 2)
                hist[bin_idx] += 1
            else:
                hist[LBP_BINS - 1] += 1
            count += 1

    if count > 0:
        hist /= count
    return hist


def extract_eoh_histogram(arr, left, top, right, bottom):
    """边缘方向直方图 (EOH)"""
    hist = np.zeros(EOH_BINS, dtype=np.float32)
    h, w = arr.shape[:2]
    step = max(1, min(right - left, bottom - top) // 40)

    for y in range(max(top + 1, 1), min(bottom, h - 1), step):
        for x in range(max(left + 1, 1), min(right, w - 1), step):
            gx = (luminance(arr[y - 1, x + 1, 0], arr[y - 1, x + 1, 1], arr[y - 1, x + 1, 2]) +
                  2 * luminance(arr[y, x + 1, 0], arr[y, x + 1, 1], arr[y, x + 1, 2]) +
                  luminance(arr[y + 1, x + 1, 0], arr[y + 1, x + 1, 1], arr[y + 1, x + 1, 2]) -
                  luminance(arr[y - 1, x - 1, 0], arr[y - 1, x - 1, 1], arr[y - 1, x - 1, 2]) -
                  2 * luminance(arr[y, x - 1, 0], arr[y, x - 1, 1], arr[y, x - 1, 2]) -
                  luminance(arr[y + 1, x - 1, 0], arr[y + 1, x - 1, 1], arr[y + 1, x - 1, 2]))

            gy = (luminance(arr[y + 1, x - 1, 0], arr[y + 1, x - 1, 1], arr[y + 1, x - 1, 2]) +
                  2 * luminance(arr[y + 1, x, 0], arr[y + 1, x, 1], arr[y + 1, x, 2]) +
                  luminance(arr[y + 1, x + 1, 0], arr[y + 1, x + 1, 1], arr[y + 1, x + 1, 2]) -
                  luminance(arr[y - 1, x - 1, 0], arr[y - 1, x - 1, 1], arr[y - 1, x - 1, 2]) -
                  2 * luminance(arr[y - 1, x, 0], arr[y - 1, x, 1], arr[y - 1, x, 2]) -
                  luminance(arr[y - 1, x + 1, 0], arr[y - 1, x + 1, 1], arr[y - 1, x + 1, 2]))

            mag = abs(gx) + abs(gy)
            if mag < 20:
                continue

            angle = math.atan2(gy, gx)
            bin_idx = int(((angle + math.pi) / (2 * math.pi)) * EOH_BINS)
            bin_idx = max(0, min(EOH_BINS - 1, bin_idx))
            hist[bin_idx] += mag

    s = hist.sum()
    if s > 0:
        hist /= s
    return hist


def extract_features_from_image(image_path):
    """从图片提取 128 维特征向量"""
    if image_path.startswith("http://") or image_path.startswith("https://"):
        raise ValueError("已禁用网络图片输入，请使用本地上传/本地文件路径")
    img = Image.open(image_path).convert("RGB")

    # 缩放到统一尺寸（与 Java 一致）
    target_w = 240
    target_h = int(target_w * img.height / img.width)
    img = img.resize((target_w, target_h), Image.LANCZOS)
    arr = np.array(img)
    img_h, img_w = arr.shape[:2]

    # 默认 ROI
    DEFAULT_LEFT = int(img_w * 0.22)
    DEFAULT_TOP = int(img_h * 0.10)
    DEFAULT_RIGHT = int(img_w * 0.78)
    DEFAULT_BOTTOM = int(img_h * 0.94)
    DEFAULT_LEFT = max(0, min(DEFAULT_LEFT, img_w - 1))
    DEFAULT_TOP = max(0, min(DEFAULT_TOP, img_h - 1))
    DEFAULT_RIGHT = max(DEFAULT_LEFT + 1, min(DEFAULT_RIGHT, img_w))
    DEFAULT_BOTTOM = max(DEFAULT_TOP + 1, min(DEFAULT_BOTTOM, img_h))

    roi = (DEFAULT_LEFT, DEFAULT_TOP, DEFAULT_RIGHT, DEFAULT_BOTTOM)
    roi = sanitize_region(*roi, img_w, img_h)

    # 肤色检测
    face = detect_face_region(arr, *roi)
    if face:
        face = sanitize_region(*face, img_w, img_h)

    # 上半身 / 下半身
    if face:
        face_cy = (face[1] + face[3]) // 2
        face_h = face[3] - face[1]
        upper = (roi[0], face_cy + face_h // 2, roi[2], face_cy + face_h * 4)
        lower = (roi[0], face_cy + face_h * 4, roi[2], roi[3])
    else:
        split_y = roi[1] + int((roi[3] - roi[1]) * 0.45)
        upper = (roi[0], roi[1], roi[2], split_y)
        lower = (roi[0], split_y, roi[2], roi[3])

    upper = sanitize_region(*upper, img_w, img_h)
    lower = sanitize_region(*lower, img_w, img_h)

    # 颜色直方图（多区域）
    upper_hist = compute_hsv_histogram(arr, *upper)
    lower_hist = compute_hsv_histogram(arr, *lower)

    color_hist = np.zeros(48, dtype=np.float32)
    # 上半身 50%
    color_hist[:16] = upper_hist[:16] * 0.50
    color_hist[32:48] = upper_hist[16:32] * 0.50
    # 下半身 35%
    color_hist[:16] += lower_hist[:16] * 0.35
    color_hist[32:48] += lower_hist[16:32] * 0.35
    # 头部 15%
    if face:
        hair_top = max(0, face[1] - (face[3] - face[1]) // 2)
        hair_bot = face[1] + (face[3] - face[1]) // 4
        hair = sanitize_region(face[0], hair_top, face[2], hair_bot, img_w, img_h)
        hair_hist = compute_hsv_histogram(arr, *hair)
        color_hist[:16] += hair_hist[:16] * 0.15
        color_hist[32:48] += hair_hist[16:32] * 0.15
    else:
        color_hist[:16] += upper_hist[:16] * 0.15
        color_hist[32:48] += upper_hist[16:32] * 0.15

    # L1 归一化
    s = color_hist.sum()
    if s > 0:
        color_hist /= s

    # 纹理直方图
    texture_hist = extract_lbp_histogram(arr, *upper)

    # 边缘方向直方图
    eoh_hist = extract_eoh_histogram(arr, *roi)

    # 人体比例
    box_w = (roi[2] - roi[0]) / img_w
    box_h = (roi[3] - roi[1]) / img_h
    aspect = box_w / max(box_h, 0.01)

    body_ratios = np.zeros(8, dtype=np.float32)
    body_ratios[0] = max(0.15, min(0.5, aspect * 0.7))
    body_ratios[1] = max(0.6, min(1.0, 0.75 + (aspect - 0.3) * 0.3))
    body_ratios[2] = max(0.3, min(1.0, box_h))
    body_ratios[3] = max(0.4, min(0.6, 0.48 + (box_h - 0.5) * 0.1))
    body_ratios[4] = max(0.8, min(1.2, 0.95 + (aspect - 0.3) * 0.2))

    if face:
        face_h_ratio = (face[3] - face[1]) / (roi[3] - roi[1])
        head_body = 1.0 / max(face_h_ratio * 7, 0.1)
        body_ratios[5] = max(0.5, min(1.0, head_body))
    else:
        body_ratios[5] = max(0.5, min(1.0, 7 / (box_h * 10)))

    body_ratios[6] = max(0.5, min(1.0, body_ratios[0] * 1.2))
    body_ratios[7] = max(0.5, min(1.0, body_ratios[6] * 0.95))

    # 姿态关键点
    pose = np.zeros(12, dtype=np.float32)
    if face:
        cx = (face[0] + face[2]) / 2 / img_w
        cy = (face[1] + face[3]) / 2 / img_h
        pose[0] = cx
        pose[1] = cy
    else:
        cx = (roi[0] + roi[2]) / 2 / img_w
        cy = (roi[1] + roi[3]) / 2 / img_h
        pose[0] = cx
        pose[1] = roi[1] / img_h + box_h * 0.12

    face_h_norm = (face[3] - face[1]) / img_h if face else box_h * 0.12
    shoulder_y = roi[1] / img_h + face_h_norm * 2.2
    shoulder_spread = box_w * 0.42
    pose[2] = cx - shoulder_spread  # 左肩
    pose[3] = shoulder_y
    pose[4] = cx + shoulder_spread  # 右肩
    pose[5] = shoulder_y

    hip_y = roi[1] / img_h + box_h * 0.55
    hip_spread = box_w * 0.22
    pose[6] = cx - hip_spread  # 左髋
    pose[7] = hip_y
    pose[8] = cx + hip_spread  # 右髋
    pose[9] = hip_y
    pose[10] = cx - hip_spread * 0.8  # 左膝
    pose[11] = roi[1] / img_h + box_h * 0.72

    # 辅助特征
    aux = np.zeros(4, dtype=np.float32)
    aux[0] = max(0, min(1, (aspect - 0.2) / 0.4))  # 体型
    # 性别概率（简化）
    color_diversity = 0
    for v in color_hist:
        color_diversity += v * v
    color_diversity = 1 - color_diversity
    male_likelihood = max(0, min(1, (aspect - 0.25) / 0.2))
    aux[1] = max(0, min(1, color_diversity * 1.5 + (1 - male_likelihood) * 0.4))
    aux[2] = 0.35  # 年龄默认中年
    aux[3] = 0  # 姿态角度

    # 合并为 128 维向量
    fv = np.zeros(DIM, dtype=np.float32)
    fv[0:48] = color_hist
    fv[48:64] = texture_hist
    fv[64:73] = eoh_hist
    fv[73:81] = body_ratios
    fv[81:93] = pose
    fv[93:97] = aux

    return fv, {
        "image_size": (img_w, img_h),
        "roi": roi,
        "face": face,
        "upper_body": upper,
        "lower_body": lower,
    }


def cosine_similarity(a, b):
    """余弦相似度（与 Java 一致）"""
    dot = np.dot(a, b)
    na = np.linalg.norm(a)
    nb = np.linalg.norm(b)
    if na == 0 or nb == 0:
        return 0.0
    return float(dot / (na * nb))


def generate_text_features(clothing="", accessories="", body_shape="", gender=""):
    """从文本生成特征向量（与 Java extractFromTarget 一致）"""
    fv = np.zeros(DIM, dtype=np.float32)

    combined = (clothing + " " + accessories).lower()
    body_combined = (body_shape + " " + gender).lower()

    # 颜色直方图
    color_hist = np.zeros(48, dtype=np.float32)

    def add_hue(bin_idx, weight):
        b = max(0, min(15, bin_idx))
        color_hist[b] += weight * 0.58
        color_hist[16 + b] += weight * 0.42
        color_hist[32 + 10] += weight * 0.25

    def add_tone(bin_idx, weight):
        b = max(0, min(15, bin_idx))
        color_hist[32 + b] += weight

    if any(k in combined for k in ["红", "red", "大红", "玫红"]): add_hue(0, 0.8)
    if any(k in combined for k in ["橙", "橘", "orange"]): add_hue(2, 0.7)
    if any(k in combined for k in ["黄", "yellow", "金"]): add_hue(4, 0.7)
    if any(k in combined for k in ["绿", "green", "墨绿", "军绿"]): add_hue(6, 0.7)
    if any(k in combined for k in ["青", "cyan", "湖蓝"]): add_hue(8, 0.6)
    if any(k in combined for k in ["浅蓝", "天蓝", "淡蓝"]): add_hue(9, 0.7)
    if any(k in combined for k in ["蓝", "blue", "深蓝", "藏青", "藏蓝"]): add_hue(10, 0.8)
    if any(k in combined for k in ["宝蓝", "靛蓝"]):
        add_hue(10, 0.5)
        add_hue(11, 0.4)
    if any(k in combined for k in ["紫", "purple"]): add_hue(12, 0.7)
    if any(k in combined for k in ["粉", "pink", "粉红"]): add_hue(14, 0.7)
    if any(k in combined for k in ["玫瑰", "rose"]): add_hue(15, 0.7)
    if any(k in combined for k in ["棕", "brown", "咖啡", "褐色"]): add_hue(1, 0.6)
    if any(k in combined for k in ["米", "beige", "卡其", "驼色"]): add_hue(3, 0.5)
    if any(k in combined for k in ["黑", "black"]): add_tone(1, 1.0)
    if any(k in combined for k in ["深灰", "炭灰"]): add_tone(4, 0.8)
    if any(k in combined for k in ["灰", "gray", "grey"]): add_tone(7, 0.8)
    if any(k in combined for k in ["浅灰", "银灰"]): add_tone(11, 0.7)
    if any(k in combined for k in ["白", "white", "米白"]): add_tone(14, 1.0)
    if any(k in combined for k in ["杏色", "肤色"]): add_tone(12, 0.5)

    s = color_hist.sum()
    if s < 0.1:
        color_hist = np.ones(48, dtype=np.float32) / 48
    else:
        color_hist /= s
    fv[0:48] = color_hist

    # 纹理
    texture = np.zeros(LBP_BINS, dtype=np.float32)
    texture[0] = 0.6
    if "条纹" in combined or "stripe" in combined:
        texture[0] = 0.2
        texture[1] = 0.3
        texture[2] = 0.3
    if "格子" in combined or "plaid" in combined or "check" in combined:
        texture[0] = 0.15
        for i in range(1, 5):
            texture[i] = 0.15
    if "花纹" in combined or "印花" in combined or "图案" in combined:
        texture[LBP_BINS - 1] = 0.5
    if "牛仔" in combined or "denim" in combined:
        texture[0] = 0.3
        texture[LBP_BINS - 1] = 0.4
    if "针织" in combined or "毛衣" in combined or "wool" in combined:
        texture[0] = 0.35
        texture[1] = 0.25
    s = texture.sum()
    if s > 0:
        texture /= s
    fv[48:64] = texture

    # EOH 均匀
    fv[64:73] = np.ones(EOH_BINS, dtype=np.float32) / EOH_BINS

    # 人体比例
    ratios = np.array([0.3, 0.75, 0.7, 0.48, 0.95, 0.7, 0.8, 0.76], dtype=np.float32)
    if "高" in body_combined or "tall" in body_combined:
        ratios[2] = 0.9
    if "矮" in body_combined or "short" in body_combined:
        ratios[2] = 0.5
    if any(k in body_combined for k in ["瘦", "slim", "苗条", "纤细"]):
        ratios[0] = 0.25
        ratios[1] = 0.7
        ratios[6] = 0.7
    if any(k in body_combined for k in ["胖", "fat", "壮", "微胖", "丰满"]):
        ratios[0] = 0.38
        ratios[1] = 0.85
        ratios[6] = 0.9
    if "女" in body_combined:
        ratios[0] = 0.26
        ratios[1] = 0.7
        ratios[6] = 0.72
    if "男" in body_combined:
        ratios[0] = 0.36
        ratios[1] = 0.85
        ratios[6] = 0.88
    fv[73:81] = ratios

    # 姿态关键点
    pose = np.array([0.5, 0.15, 0.38, 0.35, 0.62, 0.35, 0.42, 0.55, 0.58, 0.55, 0.42, 0.75], dtype=np.float32)
    fv[81:93] = pose

    # 辅助特征
    aux = np.zeros(4, dtype=np.float32)
    if any(k in body_combined for k in ["瘦", "苗条", "纤细", "slim"]):
        aux[0] = 0.2
    elif any(k in body_combined for k in ["壮", "胖", "丰满", "微胖"]):
        aux[0] = 0.8
    else:
        aux[0] = 0.5

    if "女" in gender:
        aux[1] = 0.85
    elif "男" in gender:
        aux[1] = 0.15
    else:
        aux[1] = 0.5

    aux[2] = 0.3
    aux[3] = 0
    fv[93:97] = aux

    return fv


# ==================== 主体逻辑 ====================

def main():
    parser = argparse.ArgumentParser(description="从图片提取 128 维特征向量")
    parser.add_argument("image", help="图片路径或 URL")
    parser.add_argument("--save", help="保存特征到 JSON 文件")
    parser.add_argument("--target-name", help="目标名称（保存时使用）")
    parser.add_argument("--compare-text", help="与文本特征比较，格式: '衣着|配饰|体态|性别'")
    parser.add_argument("--no-pretty", action="store_true", help="紧凑 JSON 输出")
    args = parser.parse_args()

    try:
        fv, info = extract_features_from_image(args.image)
    except Exception as e:
        print(f"❌ 提取失败: {e}", file=sys.stderr)
        return 1

    # 输出
    print("=" * 70)
    print(f"图片特征提取结果")
    print("=" * 70)
    print(f"图片:        {args.image}")
    print(f"处理尺寸:    {info['image_size']}")
    print(f"人物区域:    {info['roi']}")
    print(f"脸部区域:    {info['face']}")
    print(f"上半身:      {info['upper_body']}")
    print(f"下半身:      {info['lower_body']}")
    print("-" * 70)
    print(f"特征维度:    {DIM}")
    print(f"颜色直方图:  {fv[0:48].sum():.4f} (和应为 1.0)")
    print(f"纹理直方图:  {fv[48:64].sum():.4f}")
    print(f"边缘方向:    {fv[64:73].sum():.4f}")
    print()

    if args.compare_text:
        parts = args.compare_text.split("|")
        clothing = parts[0] if len(parts) > 0 else ""
        accessories = parts[1] if len(parts) > 1 else ""
        body_shape = parts[2] if len(parts) > 2 else ""
        gender = parts[3] if len(parts) > 3 else ""

        text_fv = generate_text_features(clothing, accessories, body_shape, gender)
        sim = cosine_similarity(fv, text_fv)
        print(f"与文本特征「{clothing}|{accessories}|{body_shape}|{gender}」对比:")
        print(f"  综合相似度:    {sim:.4f}")
        # 维度相似度
        print(f"  颜色相似度:    {cosine_similarity(fv[0:48], text_fv[0:48]):.4f}")
        print(f"  纹理相似度:    {cosine_similarity(fv[48:64], text_fv[48:64]):.4f}")
        print(f"  体态相似度:    {cosine_similarity(fv[73:81], text_fv[73:81]):.4f}")
        print(f"  姿态相似度:    {cosine_similarity(fv[81:93], text_fv[81:93]):.4f}")
        print()

    # 输出 JSON
    if args.save:
        data = {
            "image": args.image,
            "target_name": args.target_name or "未命名目标",
            "feature_vector": fv.tolist(),
            "dim": DIM,
            "info": {k: v if not isinstance(v, np.ndarray) else v.tolist()
                     for k, v in info.items()}
        }
        with open(args.save, "w", encoding="utf-8") as f:
            if args.no_pretty:
                json.dump(data, f, ensure_ascii=False)
            else:
                json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"✅ 特征已保存到: {args.save}")
    else:
        # 默认打印前 32 维
        print("前 32 维特征（前 16 为颜色直方图）:")
        print("  " + " ".join(f"{v:.4f}" for v in fv[:32]))

    return 0


if __name__ == "__main__":
    sys.exit(main())
