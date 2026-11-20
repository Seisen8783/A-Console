package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.MemberHook;

/** 见 de.robv.android.xposed.MemberHook 的说明：本包是编译期占位。 */
public abstract class XC_MethodHook {

    public XC_MethodHook() {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected Object callHookedMethod(MethodHookParam param) throws Throwable {
        return null;
    }

    public static XC_MethodHook beforeInvocation(final XC_MethodHook callback) {
        return callback;
    }

    public static XC_MethodHook afterInvocation(final XC_MethodHook callback) {
        return callback;
    }

    public interface Unhook {

        void unhook();
    }

    public static class MethodHookParam {

        public MemberHook thisObject;
        public Object[] args;

        public Object getResult() {
            return null;
        }

        public void setResult(Object result) {
        }

        public void setThrowable(Throwable throwable) {
        }

        public Throwable getThrowable() {
            return null;
        }

        public Object getExtra() {
            return null;
        }

        public Object setExtra(Object extra) {
            return null;
        }
    }
}
