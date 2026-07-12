package com.example.pedestriancapture.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public class PersonTarget {
    public final String id;
    public String name;
    public String gender;
    public String clothing;
    public String accessories;
    public String bodyShape;
    public String photoUri;
    public long createdAt;
    public boolean important;

    public PersonTarget(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.gender = "未设置";
        this.clothing = "";
        this.accessories = "";
        this.bodyShape = "";
        this.photoUri = "";
        this.createdAt = System.currentTimeMillis();
        this.important = false;
    }

    private PersonTarget(String id, boolean fromStore) {
        this.id = id;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("gender", gender);
        json.put("clothing", clothing);
        json.put("accessories", accessories);
        json.put("bodyShape", bodyShape);
        json.put("photoUri", photoUri);
        json.put("createdAt", createdAt);
        json.put("important", important);
        return json;
    }

    public static PersonTarget fromJson(JSONObject json) {
        if (json == null) {
            return new PersonTarget("未命名目标");
        }
        PersonTarget target = new PersonTarget(json.optString("id", UUID.randomUUID().toString()), true);
        target.name = json.optString("name", "未命名目标");
        target.gender = json.optString("gender", "未设置");
        target.clothing = json.optString("clothing", "");
        target.accessories = json.optString("accessories", "");
        target.bodyShape = json.optString("bodyShape", "");
        target.photoUri = json.optString("photoUri", "");
        target.createdAt = json.optLong("createdAt", System.currentTimeMillis());
        target.important = json.optBoolean("important", false);
        return target;
    }
}
