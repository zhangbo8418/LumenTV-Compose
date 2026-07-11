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
        String content = cache.get(name);
        if (StringUtils.isNotBlank(content)) return content;
        if (name.startsWith("http")) cache.put(name, content = OkHttp.string(name));
        else if (name.startsWith("assets")) cache.put(name, content = Asset.read(name));
        else if (name.startsWith("lib/")) cache.put(name, content = Asset.read("js/" + name));
        else if (name.startsWith("file:") || looksLikeLocalPath(name)) {
            cache.put(name, content = readLocal(name));
        }
        return content;
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
