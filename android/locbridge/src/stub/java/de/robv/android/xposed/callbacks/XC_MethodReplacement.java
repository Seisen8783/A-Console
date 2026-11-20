package de.robv.android.xposed.callbacks;

/** 见 de.robv.android.xposed.MemberHook 的说明：本包是编译期占位。 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
