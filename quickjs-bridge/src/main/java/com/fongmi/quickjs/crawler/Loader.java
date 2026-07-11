package com.fongmi.quickjs.crawler;

import java.net.URLClassLoader;

public class Loader {

    public Spider spider(String api, ClassLoader dex) {
        return new Spider(api, dex);
    }
}
