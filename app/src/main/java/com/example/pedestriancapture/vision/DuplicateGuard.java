package com.example.pedestriancapture.vision;

import java.util.HashMap;
import java.util.Map;

public class DuplicateGuard {
    private final Map<String, Long> lastHitAt = new HashMap<>();
    private long intervalMillis = 30_000L;

    public void setIntervalSeconds(int seconds) {
        intervalMillis = Math.max(5, seconds) * 1000L;
    }

    public boolean canCapture(String targetId) {
        long now = System.currentTimeMillis();
        Long last = lastHitAt.get(targetId);
        if (last != null && now - last < intervalMillis) {
            return false;
        }
        lastHitAt.put(targetId, now);
        return true;
    }
}
