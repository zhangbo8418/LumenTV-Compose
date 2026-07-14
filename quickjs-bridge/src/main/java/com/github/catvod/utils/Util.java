package com.github.catvod.utils;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * 对齐 TV catvod Util（QuickJS / 宿主自用）。
 * 桌面 spider.jar 的 Util/Path 由 SpiderJarClassLoader 子优先加载，不依赖本类补齐 spider API。
 */
public class Util {

    /** 对齐 Android Base64 标志位 */
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    private static final int FLAG_URL_SAFE = 8;

    public static final String OKHTTP = "okhttp/" + okHttpVersion();
    public static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    /** 对齐 TV：Base64.DEFAULT | URL_SAFE | NO_WRAP */
    public static final int URL_SAFE = DEFAULT | FLAG_URL_SAFE | NO_WRAP;

    private static String okHttpVersion() {
        try {
            return (String) Class.forName("okhttp3.OkHttp").getField("VERSION").get(null);
        } catch (Throwable e) {
            return "4.12.0";
        }
    }

    public static String base64(String s) {
        return base64(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String base64(byte[] bytes) {
        return base64(bytes, DEFAULT | NO_WRAP);
    }

    public static String base64(String s, int flags) {
        return base64(s.getBytes(StandardCharsets.UTF_8), flags);
    }

    public static String base64(byte[] bytes, int flags) {
        boolean urlSafe = (flags & FLAG_URL_SAFE) != 0;
        boolean noPadding = (flags & NO_PADDING) != 0;
        Base64.Encoder encoder = urlSafe ? Base64.getUrlEncoder() : Base64.getEncoder();
        if (noPadding) encoder = encoder.withoutPadding();
        return encoder.encodeToString(bytes);
    }

    public static byte[] decode(String s) {
        return decode(s, DEFAULT | NO_WRAP);
    }

    public static byte[] decode(String s, int flags) {
        try {
            boolean urlSafe = (flags & FLAG_URL_SAFE) != 0;
            Base64.Decoder decoder = urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder();
            return decoder.decode(s);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public static byte[] hex2byte(String s) {
        byte[] bytes = new byte[s.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = Integer.valueOf(s.substring(i * 2, i * 2 + 2), 16).byteValue();
        }
        return bytes;
    }

    public static boolean equals(String name, String md5) {
        return md5(Path.jar(name)).equalsIgnoreCase(md5);
    }

    public static String md5(String src) {
        try {
            if (StringUtils.isBlank(src)) return "";
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(src.getBytes(StandardCharsets.UTF_8));
            BigInteger no = new BigInteger(1, bytes);
            StringBuilder sb = new StringBuilder(no.toString(16));
            while (sb.length() < 32) sb.insert(0, "0");
            return sb.toString().toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    public static String md5(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] bytes = new byte[16384];
                int count;
                while ((count = fis.read(bytes)) != -1) digest.update(bytes, 0, count);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean containOrMatch(String text, String regex) {
        try {
            return text.contains(regex) || text.matches(regex);
        } catch (Exception e) {
            return false;
        }
    }

    public static String substring(String text) {
        return substring(text, 1);
    }

    public static String substring(String text, int num) {
        if (text != null && text.length() > num) return text.substring(0, text.length() - num);
        return text;
    }

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

    private static String getHostAddress(String keyword) throws SocketException {
        for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
            NetworkInterface nif = en.nextElement();
            if (!keyword.isEmpty() && !nif.getName().toLowerCase().startsWith(keyword)) continue;
            if (nif.isLoopback() || !nif.isUp()) continue;
            for (Enumeration<InetAddress> addresses = nif.getInetAddresses(); addresses.hasMoreElements(); ) {
                InetAddress addr = addresses.nextElement();
                if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress() && addr instanceof Inet4Address) {
                    return addr.getHostAddress();
                }
            }
        }
        return "";
    }

    // ---------- 桌面 spider.jar 扩展（TV 无，但 catvodspider 等桌面 jar 会调） ----------

    public static void notify(String msg) {
        if (StringUtils.isBlank(msg)) return;
        System.out.println("[catvod.notify] " + msg);
    }

    public static void notify(String msg, Integer length) {
        notify(msg);
    }

    public static void showToast(String msg, Integer length) {
        notify(msg);
    }

    public static HashMap<String, String> webHeaders(String url) {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", CHROME);
        if (StringUtils.isNotBlank(url)) {
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                if (host != null) {
                    String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
                    headers.put("Referer", scheme + "://" + host + "/");
                }
            } catch (Throwable ignored) {
            }
        }
        return headers;
    }

    public static HashMap<String, String> webHeaders(String url, String referer) {
        HashMap<String, String> headers = webHeaders(url);
        if (StringUtils.isNotBlank(referer)) headers.put("Referer", referer);
        return headers;
    }

    public static HashMap<String, String> webHeaders(String url, String referer, String cookie) {
        HashMap<String, String> headers = webHeaders(url, referer);
        if (StringUtils.isNotBlank(cookie)) headers.put("Cookie", cookie);
        return headers;
    }

    public static String stringJoin(String delimiter, Collection<String> elements) {
        if (elements == null || elements.isEmpty()) return "";
        return String.join(delimiter == null ? "" : delimiter, elements);
    }

    public static String stringJoin(Collection<String> elements, String delimiter) {
        return stringJoin(delimiter, elements);
    }

    public static String base64Encode(String s) {
        return base64(s);
    }

    public static String base64Encode(byte[] bytes) {
        return base64(bytes);
    }

    public static String base64Decode(String s) {
        try {
            return new String(decode(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static String MD5(String src) {
        return md5(src);
    }

    public static String MD5(String src, String charset) {
        try {
            if (StringUtils.isBlank(src)) return "";
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(src.getBytes(charset));
            BigInteger no = new BigInteger(1, bytes);
            StringBuilder sb = new StringBuilder(no.toString(16));
            while (sb.length() < 32) sb.insert(0, "0");
            return sb.toString().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    public static void sleep(Integer ms) {
        try {
            Thread.sleep(ms == null ? 0 : ms.longValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static JDialog showDialog(JPanel panel, String title) {
        AtomicReference<JDialog> ref = new AtomicReference<>();
        Runnable show = () -> {
            JDialog dialog = new JDialog((java.awt.Frame) null, title == null ? "" : title, true);
            dialog.setContentPane(panel);
            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            ref.set(dialog);
            dialog.setVisible(true);
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) show.run();
            else SwingUtilities.invokeAndWait(show);
        } catch (Exception e) {
            notify(title + ": " + e.getMessage());
        }
        return ref.get();
    }

    public static String ShowInputDialog(String message, Object callBack) {
        String input = JOptionPane.showInputDialog(null, message);
        if (callBack != null && input != null) {
            try {
                callBack.getClass().getMethod("done", String.class).invoke(callBack, input);
            } catch (Throwable ignored) {
            }
        }
        return input == null ? "" : input;
    }
}
