package com.noreal.locbridge

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.interfaces.IXposedHookLoadPackage
import org.json.JSONObject

/**
 * Xposed 模块入口。
 *
 * 微信的定位请求可能从主进程发起，也可能从 :appbrand（小程序逻辑层）发起，
 * 所以这里对目标包的所有进程都装一遍 hook，只在每个进程里各自订阅一份坐标流。
 */
class LocBridge : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val context = currentContext()
        val config = BridgeConfig.read(context)
        if (lpparam.packageName !in config.targets) return

        runCatching {
            LocationBridge.install(lpparam.classLoader)
            XposedBridge.log("[LocBridge] hooked ${lpparam.packageName} in ${lpparam.processName}")
        }.onFailure {
            XposedBridge.log("[LocBridge] hook failed in ${lpparam.processName}: ${it.message}")
            return
        }

        val stream = StreamClient(config.port)
        stream.start { fix ->
            FixSource.accept(fix)
            report(context, lpparam, stream)
        }
        Runtime.getRuntime().addShutdownHook(Thread { stream.stop() })
    }

    private fun report(context: Context?, lpparam: XC_LoadPackage.LoadPackageParam, stream: StreamClient) {
        val resolver = context?.contentResolver ?: return
        val fix = FixSource.fix
        val json = JSONObject().apply {
            put("pkg", lpparam.packageName)
            put("proc", lpparam.processName)
            put("hits", FixSource.hits.get())
            put("updatedAt", FixSource.updatedAt)
            put("connected", stream.connected)
            stream.lastError.get()?.let { put("error", it) }
            if (fix != null) {
                put("lat", fix.latitude)
                put("lon", fix.longitude)
                put("spd", fix.speed)
                put("brg", fix.bearing)
                put("lap", fix.lap)
            }
        }
        runCatching {
            resolver.insert(
                Uri.parse("content://${BridgeProvider.AUTHORITY}"),
                ContentValues().apply { put(BridgeProvider.KEY_STATUS, json.toString()) }
            )
        }
    }

    /** 拿到被注入进程的 Context，用来读配置和回写状态。取不到就退回默认配置。 */
    private fun currentContext(): Context? = runCatching {
        val method = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
        method.invoke(null) as? Context
    }.getOrNull()
}
