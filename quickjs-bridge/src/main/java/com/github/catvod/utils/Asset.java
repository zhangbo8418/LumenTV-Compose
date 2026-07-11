package com.github.catvod.utils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class Asset {

    public static InputStream open(String fileName) {
        try {
            String path = fileName.replace("assets://", "");
            if (path.startsWith("js/")) {
                return Asset.class.getClassLoader().getResourceAsStream(path);
            }
            return Asset.class.getClassLoader().getResourceAsStream("js/" + path);
        } catch (Exception e) {
            return null;
        }
    }

    public static String read(String fileName) {
        try (InputStream in = open(fileName)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
