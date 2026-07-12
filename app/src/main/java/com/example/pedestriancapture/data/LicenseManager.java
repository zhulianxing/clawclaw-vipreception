package com.example.pedestriancapture.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * 离线激活码管理系统
 *
 * 激活码格式：XXXX-XXXX-XXXX-XXXX（16位，含分隔符）
 * 算法：SHA-256(设备指纹 + 激活码) 前8字节 == 内嵌校验码
 *
 * 批量生成激活码时不绑定设备（通用码），使用固定 secret 进行验证。
 * 通用码方案：SHA-256(code + SECRET_SALT) 的特定 pattern 匹配。
 *
 * 试用逻辑：
 * - 首次启动记录 install_time
 * - 7天试用期内可正常使用
 * - 过期后需要输入激活码
 * - 激活码验证通过后永久解锁
 */
public class LicenseManager {

    private static final String PREFS = "license_prefs";
    private static final String KEY_INSTALL_TIME = "install_time";
    private static final String KEY_ACTIVATED = "activated";
    private static final String KEY_ACTIVATION_CODE = "activation_code";
    private static final String KEY_TRIAL_EXPIRED_SHOWN = "trial_expired_shown";

    // 试用期天数
    public static final int TRIAL_DAYS = 14;

    // 激活价格
    public static final String PRICE = "¥198";

    /**
     * 激活码验证密钥（与生成器端共享）
     * 每个激活码的前8位是 SHA-256(code + SALT) 的前8个hex字符
     */
    private static final String SALT = "ZPQNY2026PED";

    /**
     * 批量生成的激活码前缀（用于区分不同批次）
     * 激活码格式：XXXX-XXXX-XXXX-XXXX
     * 验证规则：取去掉横线的12位码，SHA-256(code + SALT) 前6个hex字符 == code 的最后6位
     */
    private static final int CHECK_LEN = 6;

    private final SharedPreferences prefs;
    private final Context context;

    public LicenseManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // 首次启动记录安装时间
        if (prefs.getLong(KEY_INSTALL_TIME, 0) == 0) {
            prefs.edit().putLong(KEY_INSTALL_TIME, System.currentTimeMillis()).apply();
        }
    }

    /**
     * 获取安装时间（首次启动时间）
     */
    public long getInstallTime() {
        return prefs.getLong(KEY_INSTALL_TIME, System.currentTimeMillis());
    }

    /**
     * 获取试用剩余天数
     */
    public int getRemainingTrialDays() {
        if (isActivated()) return Integer.MAX_VALUE;

        long installTime = getInstallTime();
        long elapsed = System.currentTimeMillis() - installTime;
        long remaining = (TRIAL_DAYS * 24L * 60L * 60L * 1000L) - elapsed;

        if (remaining <= 0) return 0;
        return (int) (remaining / (24L * 60L * 60L * 1000L)) + 1; // 向上取整
    }

    /**
     * 试用是否过期
     */
    public boolean isTrialExpired() {
        if (isActivated()) return false;
        return getRemainingTrialDays() <= 0;
    }

    /**
     * 是否已激活
     */
    public boolean isActivated() {
        return prefs.getBoolean(KEY_ACTIVATED, false);
    }

    /**
     * 获取已激活的激活码
     */
    public String getActivationCode() {
        return prefs.getString(KEY_ACTIVATION_CODE, "");
    }

    /**
     * 获取设备指纹（用于显示和绑定）
     */
    public String getDeviceFingerprint() {
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "unknown";
        String model = Build.MODEL;
        String brand = Build.BRAND;
        String raw = brand + "_" + model + "_" + androidId;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02X", hash[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return androidId.toUpperCase().substring(0, Math.min(16, androidId.length()));
        }
    }

    /**
     * 验证激活码
     *
     * 算法：
     * 1. 去掉横线，得到12位码
     * 2. 前6位 = payload，后6位 = checksum
     * 3. SHA-256(payload + SALT) 前6个hex字符（大写）== checksum
     *
     * @param code 激活码，格式 XXXX-XXXX-XXXX-XXXX
     * @return true 如果验证通过
     */
    public boolean validateCode(String code) {
        if (code == null) return false;

        // 清理输入：去掉横线、空格，转大写
        String clean = code.replace("-", "").replace(" ", "").trim().toUpperCase();

        // 长度必须12位
        if (clean.length() != 12) return false;

        // 只允许字母数字
        if (!clean.matches("[A-Z0-9]{12}")) return false;

        String payload = clean.substring(0, 6);
        String checksum = clean.substring(6, 12);

        // 计算校验码
        String expected = sha256Hex(payload + SALT).substring(0, CHECK_LEN).toUpperCase();

        return expected.equals(checksum);
    }

    /**
     * 激活（保存激活码）
     */
    public boolean activate(String code) {
        if (!validateCode(code)) return false;

        String cleanCode = code.replace("-", "").replace(" ", "").trim().toUpperCase();
        String formatted = formatCode(cleanCode);

        prefs.edit()
                .putBoolean(KEY_ACTIVATED, true)
                .putString(KEY_ACTIVATION_CODE, formatted)
                .putBoolean(KEY_TRIAL_EXPIRED_SHOWN, true)
                .apply();

        return true;
    }

    /**
     * 重置激活（用于测试）
     */
    public void reset() {
        prefs.edit().clear().apply();
        prefs.edit().putLong(KEY_INSTALL_TIME, System.currentTimeMillis()).apply();
    }

    /**
     * 标记试用过期提示已显示
     */
    public boolean isTrialExpiredShown() {
        return prefs.getBoolean(KEY_TRIAL_EXPIRED_SHOWN, false);
    }

    public void setTrialExpiredShown(boolean shown) {
        prefs.edit().putBoolean(KEY_TRIAL_EXPIRED_SHOWN, shown).apply();
    }

    /**
     * 格式化激活码：XXXX-XXXX-XXXX-XXXX
     */
    public static String formatCode(String cleanCode) {
        if (cleanCode == null || cleanCode.length() < 12) return cleanCode;
        cleanCode = cleanCode.replace("-", "").replace(" ", "").toUpperCase();
        return cleanCode.substring(0, 4) + "-" + cleanCode.substring(4, 8) + "-" +
               cleanCode.substring(8, 12) + "-XXXX";
    }

    /**
     * 格式化激活码（完整显示）
     */
    public static String formatCodeFull(String cleanCode) {
        if (cleanCode == null || cleanCode.length() < 12) return cleanCode;
        cleanCode = cleanCode.replace("-", "").replace(" ", "").toUpperCase();
        return cleanCode.substring(0, 4) + "-" + cleanCode.substring(4, 8) + "-" +
               cleanCode.substring(8, 12);
    }

    /**
     * SHA-256 转十六进制字符串
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 获取试用状态描述
     */
    public String getStatusText() {
        if (isActivated()) {
            return "已激活 · 永久授权";
        }
        int remaining = getRemainingTrialDays();
        if (remaining <= 0) {
            return "试用已过期 · 请激活";
        }
        return "试用中 · 剩余 " + remaining + " 天";
    }
}
