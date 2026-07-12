package com.example.pedestriancapture.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class LocalStore {
    private static final String PREFS = "pedestrian_capture_store";
    private static final String TARGETS = "targets";
    private static final String RECORDS = "records";
    private static final int MAX_RECORDS = 300;

    private final SharedPreferences prefs;
    private int maxRecords = 300;

    public LocalStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void setMaxRecords(int max) {
        this.maxRecords = max;
    }

    public List<PersonTarget> loadTargets() {
        List<PersonTarget> targets = new ArrayList<>();
        JSONArray array = readArray(TARGETS);
        for (int i = 0; i < array.length(); i++) {
            targets.add(PersonTarget.fromJson(array.optJSONObject(i)));
        }
        return targets;
    }

    public void saveTarget(PersonTarget target) {
        List<PersonTarget> targets = loadTargets();
        boolean replaced = false;
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).id.equals(target.id)) {
                targets.set(i, target);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            targets.add(0, target);
        }
        writeTargets(targets);
    }

    public List<CaptureRecord> loadRecords() {
        List<CaptureRecord> records = new ArrayList<>();
        JSONArray array = readArray(RECORDS);
        for (int i = 0; i < array.length(); i++) {
            records.add(CaptureRecord.fromJson(array.optJSONObject(i)));
        }
        return records;
    }

    public void saveRecord(CaptureRecord record) {
        List<CaptureRecord> records = loadRecords();
        records.add(0, record);
        while (records.size() > maxRecords) {
            int lastIndex = records.size() - 1;
            if (records.get(lastIndex).locked) {
                break;
            }
            records.remove(lastIndex);
        }
        writeRecords(records);
    }

    public void deleteTarget(String targetId) {
        List<PersonTarget> targets = loadTargets();
        Iterator<PersonTarget> it = targets.iterator();
        while (it.hasNext()) {
            if (it.next().id.equals(targetId)) {
                it.remove();
                break;
            }
        }
        writeTargets(targets);
    }

    public void clearTargets() {
        prefs.edit().putString(TARGETS, "[]").apply();
    }

    public void deleteRecord(String recordId) {
        List<CaptureRecord> records = loadRecords();
        Iterator<CaptureRecord> it = records.iterator();
        while (it.hasNext()) {
            if (it.next().id.equals(recordId)) {
                it.remove();
                break;
            }
        }
        writeRecords(records);
    }

    public void clearRecords() {
        prefs.edit().putString(RECORDS, "[]").apply();
    }

    private JSONArray readArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void writeTargets(List<PersonTarget> targets) {
        JSONArray array = new JSONArray();
        for (PersonTarget target : targets) {
            try {
                array.put(target.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(TARGETS, array.toString()).apply();
    }

    private void writeRecords(List<CaptureRecord> records) {
        JSONArray array = new JSONArray();
        for (CaptureRecord record : records) {
            try {
                array.put(record.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(RECORDS, array.toString()).apply();
    }
}
