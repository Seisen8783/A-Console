package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Xposed 经典 API 的编译期占位。
 *
 * 运行时这个包名空间由 LSPosed / LSPatch 的框架实现接管，这里只负责让模块在
 * 没有引入外部 jar 的情况下也能编译通过。方法签名必须与真实 API 保持一致，
 * 否则注入到目标进程后会抛 NoSuchMethodError。
 */
public interface MemberHook {

    Member getMember();
}
