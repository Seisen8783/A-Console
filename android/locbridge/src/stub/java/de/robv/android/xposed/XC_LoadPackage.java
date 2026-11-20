package de.robv.android.xposed;

/** 见 de.robv.android.xposed.MemberHook 的说明：本包是编译期占位。 */
public interface XC_LoadPackage {

    class LoadPackageParam {

        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean firstApplication;
    }
}
