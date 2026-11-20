package de.robv.android.xposed.interfaces;

import de.robv.android.xposed.XC_LoadPackage;

/** 见 de.robv.android.xposed.MemberHook 的说明：本包是编译期占位。 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
