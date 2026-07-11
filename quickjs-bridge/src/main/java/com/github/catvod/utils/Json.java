package com.github.catvod.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对齐 TV catvod Json，并补充桌面 spider.jar 扩展（toJson / safeObject(String)）。
 */
public class Json {

    private static final Gson GSON = new Gson();

    public static Gson get() {
        return GSON;
    }

    public static JsonElement parse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (Throwable e) {
            return JsonParser.parseString(json == null ? "{}" : json);
        }
    }

    public static boolean isObj(String text) {
        try {
            if (StringUtils.isBlank(text)) return false;
            new JSONObject(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isArray(String text) {
        try {
            if (StringUtils.isBlank(text)) return false;
            new JSONArray(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isEmpty(JsonObject obj, String key) {
        if (!obj.has(key)) return true;
        JsonElement element = obj.get(key);
        if (element.isJsonNull()) return true;
        if (element.isJsonArray()) return element.getAsJsonArray().isEmpty();
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString().trim().isEmpty();
        }
        return true;
    }

    public static String safeString(JsonObject obj, String key) {
        try {
            return obj.getAsJsonPrimitive(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static List<String> safeListString(JsonObject obj, String key) {
        List<String> result = new ArrayList<>();
        if (!obj.has(key)) return result;
        if (obj.get(key).isJsonObject()) result.add(safeString(obj, key));
        else for (JsonElement opt : obj.getAsJsonArray(key)) result.add(opt.getAsString());
        return result;
    }

    public static List<JsonElement> safeListElement(JsonObject obj, String key) {
        List<JsonElement> result = new ArrayList<>();
        if (!obj.has(key)) return result;
        if (obj.get(key).isJsonObject()) result.add(obj.get(key).getAsJsonObject());
        else for (JsonElement opt : obj.getAsJsonArray(key)) result.add(opt.getAsJsonObject());
        return result;
    }

    /** 对齐 TV：safeObject(JsonElement) */
    public static JsonObject safeObject(JsonElement element) {
        try {
            if (element.isJsonPrimitive()) element = parse(element.getAsJsonPrimitive().getAsString());
            return element.getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    /** 桌面 jar 扩展 */
    public static JsonObject safeObject(String json) {
        try {
            if (StringUtils.isBlank(json)) return new JsonObject();
            return safeObject(parse(json));
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    public static Map<String, String> toMap(String json) {
        return StringUtils.isBlank(json) ? null : toMap(parse(json));
    }

    public static Map<String, String> toMap(JsonElement element) {
        Map<String, String> map = new HashMap<>();
        JsonObject object = safeObject(element);
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            map.put(entry.getKey(), safeString(object, entry.getKey()));
        }
        return map;
    }

    public static String toJson(Object obj) {
        try {
            return GSON.toJson(obj);
        } catch (Throwable e) {
            return obj == null ? "null" : String.valueOf(obj);
        }
    }

    public static <T> T parseSafe(String json, Type type) {
        try {
            return GSON.fromJson(json, type);
        } catch (Throwable e) {
            return null;
        }
    }
}
