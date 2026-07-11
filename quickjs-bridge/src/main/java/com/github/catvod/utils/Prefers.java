package com.github.catvod.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对齐 TV SharedPreferences：进程内缓存 + 磁盘持久化，供 JS local.get/set/delete 使用。
 */
public class Prefers {

    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private static final Object lock = new Object();
    private static File storeFile;
    private static boolean loaded;

    public static void init(File file) {
        synchronized (lock) {
            storeFile = file;
            loaded = false;
            ensureLoaded();
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (storeFile == null) {
            storeFile = defaultStoreFile();
        }
        File parent = storeFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (!storeFile.isFile()) return;
        try (FileInputStream in = new FileInputStream(storeFile)) {
            Properties props = new Properties();
            props.load(in);
            for (String name : props.stringPropertyNames()) {
                cache.put(name, props.getProperty(name, ""));
            }
        } catch (Exception ignored) {
        }
    }

    private static File defaultStoreFile() {
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase();
        File dir;
        if (os.contains("mac")) {
            dir = new File(home, "Library/Caches/Lumen-TV/data/cache");
        } else if (os.contains("win")) {
            String appData = System.getenv("AppData");
            dir = new File(appData != null ? appData : home, "Lumen-TV/cache");
        } else {
            dir = new File(home, ".cache/Lumen-TV");
        }
        return new File(dir, "js-prefers.properties");
    }

    private static void persist() {
        ensureLoaded();
        if (storeFile == null) return;
        try {
            File parent = storeFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Properties props = new Properties();
            props.putAll(cache);
            try (FileOutputStream out = new FileOutputStream(storeFile)) {
                props.store(out, "LumenTV JS local prefers");
            }
        } catch (Exception ignored) {
        }
    }

    public static String getString(String key) {
        return getString(key, "");
    }

    public static String getString(String key, String defaultValue) {
        synchronized (lock) {
            ensureLoaded();
            return cache.getOrDefault(key, defaultValue);
        }
    }

    public static void put(String key, Object obj) {
        if (obj == null) return;
        synchronized (lock) {
            ensureLoaded();
            cache.put(key, String.valueOf(obj));
            persist();
        }
    }

    public static void remove(String key) {
        synchronized (lock) {
            ensureLoaded();
            cache.remove(key);
            persist();
        }
    }
}
