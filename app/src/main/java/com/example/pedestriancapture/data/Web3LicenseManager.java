package com.example.pedestriancapture.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Web3 激活码管理器 — 基于 Polygon 链上 NFT 验证
 *
 * 流程：
 * 1. 用户导入钱包私钥
 * 2. APP 通过 LicenseQuery 合约验证用户是否持有对应 NFT
 * 3. 验证通过 → 解锁 APP 全部功能
 * 4. 未持有 → 引导用户到 daix.fun 购买
 *
 * 同时保留离线激活码作为 fallback（无网络时使用）
 */
public class Web3LicenseManager {

    // ===== 合约配置 =====
    private static final String RPC_URL = "https://polygon-bor-rpc.publicnode.com";
    private static final String LICENSE_QUERY = "0xb36Fd748026097e0F18A644452E739DECf4d0686";
    private static final String LICENSE_NFT = "0x7Ed65226C66b188f66AA0e5483917B1C33a41225";
    private static final long CHAIN_ID = 137;

    // 本APP的包名（用于 getAppByPackageName）
    private final String packageName;

    // ===== 本地存储 =====
    private static final String PREFS = "web3_license";
    private static final String KEY_PRIVATE = "private_key";
    private static final String KEY_ADDRESS = "wallet_address";
    private static final String KEY_VERIFIED = "nft_verified";
    private static final String KEY_APP_ID = "chain_app_id";
    private static final String KEY_TRIAL_START = "trial_start";
    private static final String KEY_OFFLINE_ACTIVATED = "offline_activated";

    private final SharedPreferences prefs;
    private final OkHttpClient http;
    private final ExecutorService executor;
    private int nextId = 1;

    // 试用期天数
    private static final long TRIAL_DAYS = 7;
    private static final long DAY_MS = 86400000L;

    public Web3LicenseManager(Context ctx, String packageName) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.packageName = packageName;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor();
    }

    // ===== 钱包管理 =====

    public boolean hasWallet() {
        return prefs.contains(KEY_ADDRESS);
    }

    public String getAddress() {
        return prefs.getString(KEY_ADDRESS, null);
    }

    public String getPrivateKey() {
        return prefs.getString(KEY_PRIVATE, null);
    }

    public boolean importWallet(String privateKeyHex) {
        if (privateKeyHex.startsWith("0x")) privateKeyHex = privateKeyHex.substring(2);
        if (privateKeyHex.length() != 64) return false;
        try {
            String address = addressFromPrivateKey(privateKeyHex);
            prefs.edit()
                    .putString(KEY_PRIVATE, privateKeyHex)
                    .putString(KEY_ADDRESS, address)
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void clearWallet() {
        prefs.edit()
                .remove(KEY_PRIVATE)
                .remove(KEY_ADDRESS)
                .remove(KEY_VERIFIED)
                .apply();
    }

    // ===== NFT 验证 =====

    /**
     * 验证当前钱包是否持有本APP的NFT许可证
     * @param callback 回调
     */
    public void verifyLicense(VerifyCallback callback) {
        String address = getAddress();
        if (address == null) {
            callback.onResult(false, "未导入钱包", -1);
            return;
        }

        executor.submit(() -> {
            try {
                // Step 1: 通过包名获取 appId
                long appId = getAppIdByPackageName();
                if (appId <= 0) {
                    callback.onResult(false, "APP未在链上注册", -1);
                    return;
                }

                prefs.edit().putLong(KEY_APP_ID, appId).apply();

                // Step 2: 调用 LicenseQuery.verifyLicense(address, appId)
                String data = "0xae0226b3"  // verifyLicense(address,uint256) selector
                        + padAddress(address)
                        + padLong(appId);

                String result = ethCall(LICENSE_QUERY, data);
                if (result == null || result.length() < 130) {
                    callback.onResult(false, "链上查询失败", appId);
                    return;
                }

                // 解码返回值: (bool hasLicense, uint256 tokenId)
                String hex = result.startsWith("0x") ? result.substring(2) : result;
                boolean hasLicense = !hex.substring(0, 64).equals("0000000000000000000000000000000000000000000000000000000000000000");
                long tokenId = Long.parseLong(hex.substring(64, 128), 16);

                prefs.edit().putBoolean(KEY_VERIFIED, hasLicense).apply();

                if (hasLicense) {
                    callback.onResult(true, "NFT验证通过", appId);
                } else {
                    callback.onResult(false, "未持有NFT许可证", appId);
                }
            } catch (Exception e) {
                callback.onResult(false, "验证失败: " + e.getMessage(), -1);
            }
        });
    }

    /**
     * 获取缓存的验证状态
     */
    public boolean isVerified() {
        return prefs.getBoolean(KEY_VERIFIED, false);
    }

    /**
     * 获取缓存的 appId
     */
    public long getCachedAppId() {
        return prefs.getLong(KEY_APP_ID, -1);
    }

    // ===== 试用期管理 =====

    public long getTrialStart() {
        long start = prefs.getLong(KEY_TRIAL_START, 0);
        if (start == 0) {
            start = System.currentTimeMillis();
            prefs.edit().putLong(KEY_TRIAL_START, start).apply();
        }
        return start;
    }

    public boolean isTrialExpired() {
        long elapsed = System.currentTimeMillis() - getTrialStart();
        return elapsed > TRIAL_DAYS * DAY_MS;
    }

    public long getTrialRemaining() {
        long elapsed = System.currentTimeMillis() - getTrialStart();
        long remaining = TRIAL_DAYS * DAY_MS - elapsed;
        return Math.max(0, remaining);
    }

    public boolean isActivated() {
        return isVerified() || prefs.getBoolean(KEY_OFFLINE_ACTIVATED, false);
    }

    public String getStatusText() {
        if (isVerified()) return "✅ 链上NFT已验证";
        if (prefs.getBoolean(KEY_OFFLINE_ACTIVATED, false)) return "✅ 离线激活码已激活";
        long remaining = getTrialRemaining();
        if (remaining <= 0) return "🔒 试用已过期，请购买数字凭证";
        long days = remaining / DAY_MS;
        long hours = (remaining % DAY_MS) / 3600000;
        return "⏳ 试用剩余: " + days + "天" + hours + "小时";
    }

    // ===== 离线激活码（fallback） =====

    public boolean activateOffline(String code) {
        // 简化的离线验证（保留原有逻辑）
        boolean valid = validateOfflineCode(code);
        if (valid) {
            prefs.edit().putBoolean(KEY_OFFLINE_ACTIVATED, true).apply();
        }
        return valid;
    }

    private boolean validateOfflineCode(String code) {
        // 简单格式验证，实际可扩展
        return code != null && code.length() >= 16 && code.matches("[A-Z0-9-]+");
    }

    // ===== RPC 调用 =====

    private String ethCall(String to, String data) throws Exception {
        JSONObject callObj = new JSONObject();
        callObj.put("to", to);
        callObj.put("data", data);
        JSONArray params = new JSONArray();
        params.put(callObj);
        params.put("latest");
        return rpcCall("eth_call", params);
    }

    private String rpcCall(String method, JSONArray params) throws Exception {
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", nextId++);
        req.put("method", method);
        req.put("params", params);

        RequestBody body = RequestBody.create(req.toString(),
                MediaType.parse("application/json"));
        Request request = new Request.Builder().url(RPC_URL).post(body).build();

        try (Response response = http.newCall(request).execute()) {
            JSONObject resp = new JSONObject(response.body().string());
            if (resp.has("error")) return null;
            return resp.getString("result");
        }
    }

    private long getAppIdByPackageName() throws Exception {
        // 先检查缓存
        long cached = getCachedAppId();
        if (cached > 0) return cached;

        // 调用 LicenseQuery.getAppByPackageName(string)
        String data = "0x58e30592"  // getAppByPackageName selector
                + encodeString(packageName);

        String result = ethCall(LICENSE_QUERY, data);
        if (result == null || result.length() < 130) return -1;

        String hex = result.startsWith("0x") ? result.substring(2) : result;
        long appId = Long.parseLong(hex.substring(0, 64), 16);
        return appId;
    }

    // ===== 编码工具 =====

    private static String padAddress(String addr) {
        String clean = addr.startsWith("0x") ? addr.substring(2) : addr;
        return String.format("%64s", clean).replace(' ', '0');
    }

    private static String padLong(long val) {
        return String.format("%064x", val);
    }

    private static String encodeString(String str) {
        byte[] bytes = str.getBytes();
        String hex = bytesToHex(bytes);
        String offset = String.format("%064x", 32);
        String length = String.format("%064x", bytes.length);
        String padded = hex;
        while (padded.length() % 64 != 0) padded += "00";
        return offset + length + padded;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    // ===== 以太坊加密 =====

    private static final X9ECParameters CURVE_PARAMS = CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters CURVE = new ECDomainParameters(
            CURVE_PARAMS.getCurve(), CURVE_PARAMS.getG(), CURVE_PARAMS.getN(), CURVE_PARAMS.getH());

    public static String addressFromPrivateKey(String privateKeyHex) {
        if (privateKeyHex.startsWith("0x")) privateKeyHex = privateKeyHex.substring(2);
        BigInteger privKey = new BigInteger(privateKeyHex, 16);
        ECPoint point = CURVE.getG().multiply(privKey).normalize();
        byte[] encoded = point.getEncoded(false);
        byte[] xy = new byte[64];
        System.arraycopy(encoded, 1, xy, 0, 64);

        KeccakDigest digest = new KeccakDigest(256);
        digest.update(xy, 0, 64);
        byte[] hash = new byte[32];
        digest.doFinal(hash, 0);

        byte[] address = new byte[20];
        System.arraycopy(hash, 12, address, 0, 20);
        return "0x" + bytesToHex(address);
    }

    // ===== 回调接口 =====

    public interface VerifyCallback {
        void onResult(boolean verified, String message, long appId);
    }
}
