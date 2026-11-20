package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

import de.robv.android.xposed.callbacks.XC_MethodHook;

/** 见 de.robv.android.xposed.MemberHook 的说明：本包是编译期占位。 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
    }

    public static void log(Throwable throwable) {
    }

    public static void setHiddenApiExemptions(Class<?>... classes) {
    }

    public static XC_MethodHook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        return null;
    }

    public static XC_MethodHook hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return null;
    }
}
