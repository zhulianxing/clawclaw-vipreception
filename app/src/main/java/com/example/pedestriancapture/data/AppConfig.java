package com.example.pedestriancapture.data;

import android.content.Context;
import android.content.SharedPreferences;

public class AppConfig {
    private static final String PREFS = "pedestrian_capture_config";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String KEY_DEDUP_INTERVAL = "dedup_interval";
    private static final String KEY_MAX_RECORDS = "max_records";
    private static final String KEY_VIBRATION = "vibration_enabled";
    private static final String KEY_ALARM_SOUND = "alarm_sound_enabled";

    private final SharedPreferences prefs;

    public AppConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public float getThreshold() {
        return prefs.getFloat(KEY_THRESHOLD, 0.72f);
    }

    public void setThreshold(float value) {
        prefs.edit().putFloat(KEY_THRESHOLD, value).apply();
    }

    public int getDedupInterval() {
        return prefs.getInt(KEY_DEDUP_INTERVAL, 30);
    }

    public void setDedupInterval(int seconds) {
        prefs.edit().putInt(KEY_DEDUP_INTERVAL, seconds).apply();
    }

    public int getMaxRecords() {
        return prefs.getInt(KEY_MAX_RECORDS, 300);
    }

    public void setMaxRecords(int max) {
        prefs.edit().putInt(KEY_MAX_RECORDS, max).apply();
    }

    public boolean isVibrationEnabled() {
        return prefs.getBoolean(KEY_VIBRATION, true);
    }

    public void setVibrationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply();
    }

    public boolean isAlarmSoundEnabled() {
        return prefs.getBoolean(KEY_ALARM_SOUND, true);
    }

    public void setAlarmSoundEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ALARM_SOUND, enabled).apply();
    }
}
