package com.fongmi.quickjs.method;

import com.whl.quickjs.wrapper.QuickJSContext;

public class Console implements QuickJSContext.Console {

    @Override
    public void log(String info) {
        System.out.println("[quickjs] " + info);
    }

    @Override
    public void info(String info) {
        System.out.println("[quickjs] " + info);
    }

    @Override
    public void warn(String info) {
        System.err.println("[quickjs] " + info);
    }

    @Override
    public void error(String info) {
        System.err.println("[quickjs] " + info);
    }
}
