package com.fongmi.quickjs.utils;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class Module {

    private static final int MAX_SIZE = 50;
    private final Map<String, String> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_SIZE;
        }
    };

    public static Module get() {
        return Loader.INSTANCE;
    }

    public String fetch(String name) {
        String cached = cache.get(name);
        if (StringUtils.isNotBlank(cached)) return cached;

        String content = null;
        if (name.startsWith("http")) {
            content = OkHttp.string(name);
            // drpy 等会把 lib/* 解析成 https://.../js/lib/*，远端常无此文件；回落到包内 assets
            if (StringUtils.isBlank(content) || looksLikeHtml(content)) {
                String libPath = toLibAssetPath(name);
                if (libPath != null) content = Asset.read("js/" + libPath);
            }
            if (StringUtils.isNotBlank(content)) cache.put(name, content);
        } else if (name.startsWith("assets")) {
            content = Asset.read(name);
            if (StringUtils.isNotBlank(content)) cache.put(name, content);
        } else if (name.startsWith("lib/")) {
            content = Asset.read("js/" + name);
            if (StringUtils.isNotBlank(content)) cache.put(name, content);
        } else if (name.startsWith("file:") || looksLikeLocalPath(name)) {
            content = readLocal(name);
            if (StringUtils.isNotBlank(content)) cache.put(name, content);
        }
        return content != null ? content : "";
    }

    private static boolean looksLikeHtml(String content) {
        if (StringUtils.isBlank(content)) return false;
        String trimmed = content.stripLeading();
        return trimmed.startsWith("<!") || trimmed.regionMatches(true, 0, "<html", 0, 5);
    }

    /** https://host/.../js/lib/foo.js → lib/foo.js */
    private static String toLibAssetPath(String name) {
        if (StringUtils.isBlank(name)) return null;
        if (name.startsWith("lib/")) return name;
        int idx = name.indexOf("/lib/");
        if (idx < 0) return null;
        return "lib/" + name.substring(idx + 5);
    }

    private static boolean looksLikeLocalPath(String name) {
        if (StringUtils.isBlank(name)) return false;
        if (name.startsWith("/") || name.startsWith("./") || name.startsWith("../")) return true;
        // Windows: C:\... or C:/...
        return name.length() > 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':' &&
            (name.charAt(2) == '/' || name.charAt(2) == '\\');
    }

    private static String readLocal(String name) {
        try {
            String path = name;
            if (path.startsWith("file:")) {
                path = path.substring("file:".length());
                while (path.startsWith("//")) path = path.substring(1);
                // file:///Users/... → /Users/...
                if (path.length() >= 3 && path.charAt(0) == '/' && Character.isLetter(path.charAt(1)) && path.charAt(2) == ':') {
                    path = path.substring(1);
                }
            }
            File file = new File(path);
            if (!file.isFile()) return "";
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public void clear() {
        cache.clear();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}
