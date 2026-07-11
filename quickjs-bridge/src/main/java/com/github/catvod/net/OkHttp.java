package com.github.catvod.net;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OkHttp {

    private static OkHttpClient client;

    public static OkHttpClient client() {
        if (client != null) return client;
        return client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public static OkHttpClient client(boolean redirect, long timeout) {
        return client().newBuilder()
                .followRedirects(redirect)
                .followSslRedirects(redirect)
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    public static String string(String url) {
        return string(url, new HashMap<>());
    }

    public static String string(String url, Map<String, String> header) {
        try {
            Request.Builder builder = new Request.Builder().url(url);
            if (header != null) header.forEach(builder::addHeader);
            try (Response response = client().newCall(builder.build()).execute()) {
                if (response.body() == null) return "";
                return response.body().string();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
