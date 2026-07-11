package com.github.catvod.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Json {

    public static JsonElement parse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (Throwable e) {
            return new JsonParser().parse(json);
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

    public static Map<String, String> toMap(String json) {
        return StringUtils.isBlank(json) ? null : toMap(parse(json));
    }

    public static Map<String, String> toMap(JsonElement element) {
        Map<String, String> map = new HashMap<>();
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonPrimitive()) {
                map.put(entry.getKey(), value.getAsString());
            }
        }
        return map;
    }

    /** spider.jar 常用；子优先 ClassLoader 下一般走 jar 内实现，此处作兜底 */
    public static String toJson(Object obj) {
        try {
            return new com.google.gson.Gson().toJson(obj);
        } catch (Throwable e) {
            return obj == null ? "null" : String.valueOf(obj);
        }
    }

    public static JsonObject safeObject(String json) {
        try {
            if (StringUtils.isBlank(json)) return new JsonObject();
            JsonElement el = parse(json);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Throwable e) {
            return new JsonObject();
        }
    }
}
