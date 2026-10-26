package com.astos.shell;

import android.content.Context;

import java.lang.reflect.Method;

/** System property get/set (hidden API via reflection — platform-signed). */
public final class SystemProp {

    private SystemProp() {}

    public static String get(Context c, String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String set(Context c, String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, value);
            return "ok";
        } catch (Throwable e) {
            return "denied: " + e.getCause();
        }
    }
}
