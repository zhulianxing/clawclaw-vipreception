package com.example.pedestriancapture.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public class CaptureRecord {
    public final String id;
    public String targetId;
    public String targetName;
    public long capturedAt;
    public float score;
    public String mode;
    public String imagePath;
    public String videoPath;
    public boolean locked;

    public CaptureRecord(String targetId, String targetName, float score, String mode) {
        this.id = UUID.randomUUID().toString();
        this.targetId = targetId;
        this.targetName = targetName;
        this.capturedAt = System.currentTimeMillis();
        this.score = score;
        this.mode = mode;
        this.imagePath = "";
        this.videoPath = "";
        this.locked = false;
    }

    private CaptureRecord(String id) {
        this.id = id;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("targetId", targetId);
        json.put("targetName", targetName);
        json.put("capturedAt", capturedAt);
        json.put("score", score);
        json.put("mode", mode);
        json.put("imagePath", imagePath);
        json.put("videoPath", videoPath);
        json.put("locked", locked);
        return json;
    }

    public static CaptureRecord fromJson(JSONObject json) {
        if (json == null) {
            return new CaptureRecord("", "未知目标", 0f, "人体特征");
        }
        CaptureRecord record = new CaptureRecord(json.optString("id", UUID.randomUUID().toString()));
        record.targetId = json.optString("targetId", "");
        record.targetName = json.optString("targetName", "未知目标");
        record.capturedAt = json.optLong("capturedAt", System.currentTimeMillis());
        record.score = (float) json.optDouble("score", 0.0);
        record.mode = json.optString("mode", "人体特征");
        record.imagePath = json.optString("imagePath", "");
        record.videoPath = json.optString("videoPath", "");
        record.locked = json.optBoolean("locked", false);
        return record;
    }
}
