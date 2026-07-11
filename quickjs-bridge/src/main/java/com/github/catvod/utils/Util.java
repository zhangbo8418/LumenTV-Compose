package com.github.catvod.utils;

import org.apache.commons.lang3.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Enumeration;

public class Util {

    public static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

    public static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] decode(String s) {
        return Base64.getDecoder().decode(s);
    }

    public static String md5(String src) {
        try {
            if (StringUtils.isBlank(src)) return "";
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /**
     * 对齐 TV：优先有线/无线网卡的局域网 IPv4，供 getProxy(false) / js2Proxy 使用。
     */
    public static String getIp() {
        try {
            String ip = getHostAddress("en");
            if (!ip.isEmpty()) return ip;
            ip = getHostAddress("eth");
            if (!ip.isEmpty()) return ip;
            ip = getHostAddress("wlan");
            if (!ip.isEmpty()) return ip;
            ip = getHostAddress("wl");
            if (!ip.isEmpty()) return ip;
            return getHostAddress("");
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * spider.jar Init.init() 会调用；桌面端用日志代替 Android Toast。
     * 注意：URLClassLoader 父优先会加载本类而非 jar 内 Util。
     */
    public static void notify(String msg) {
        if (msg == null || msg.isBlank()) return;
        System.out.println("[catvod.notify] " + msg);
    }

    public static void notify(String msg, Integer length) {
        notify(msg);
    }

    public static void showToast(String msg, Integer length) {
        notify(msg);
    }

    private static String getHostAddress(String keyword) throws Exception {
        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        while (en.hasMoreElements()) {
            NetworkInterface nif = en.nextElement();
            if (!keyword.isEmpty() && !nif.getName().toLowerCase().startsWith(keyword)) continue;
            if (nif.isLoopback() || !nif.isUp()) continue;
            Enumeration<InetAddress> addresses = nif.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress() && addr instanceof Inet4Address) {
                    return addr.getHostAddress();
                }
            }
        }
        return "";
    }
}
