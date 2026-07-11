package com.fongmi.quickjs.method;

import com.github.catvod.utils.Prefers;
import com.whl.quickjs.wrapper.JSMethod;
import org.apache.commons.lang3.StringUtils;

public class Local {

    private String getKey(String rule, String key) {
        return "cache_" + (StringUtils.isBlank(rule) ? "" : rule + "_") + key;
    }

    @JSMethod
    public String get(String rule, String key) {
        return Prefers.getString(getKey(rule, key));
    }

    @JSMethod
    public void set(String rule, String key, String value) {
        Prefers.put(getKey(rule, key), value);
    }

    @JSMethod
    public void delete(String rule, String key) {
        Prefers.remove(getKey(rule, key));
    }
}
