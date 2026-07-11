package com.github.catvod;

import com.github.catvod.utils.Util;

public class Proxy {

    private static volatile int port = 9978;

    public static void setPort(int value) {
        port = value;
    }

    public static int getPort() {
        return port;
    }

    public static String getUrl(boolean local) {
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + getPort() + "/proxy";
    }
}
