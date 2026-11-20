package com.noreal.locbridge

import android.location.Location
import android.location.LocationListener
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_MethodHook
import java.lang.reflect.Proxy
import java.util.function.Consumer

/**
 * 把目标进程里读到的定位换成本机的假坐标。
 *
 * 覆盖三条读取路径：
 * - `getLastKnownLocation(String)`
 * - `getCurrentLocation(String, CancellationSignal, Executor, Consumer)`
 * - `requestLocationUpdates(...)` 的全部重载（参数里的 LocationListener 换成代理）
 *
 * 换掉的对象是自己 new 出来的 Location，没有系统打上的 mock 标记，
 * 所以下游无论是微信自己还是腾讯位置服务，都不会把它当模拟定位丢掉。
 */
internal object LocationBridge {

    fun install(classLoader: ClassLoader) {
        val managerClass = runCatching {
            classLoader.loadClass("android.location.LocationManager")
        }.getOrNull() ?: return
        hookLastKnownLocation(managerClass)
        hookCurrentLocation(managerClass)
        hookRequestLocationUpdates(managerClass)
        hookMockFlag(classLoader)
        XposedBridge.log("[LocBridge] LocationManager hooks installed")
    }

    /**
     * 兜底：把坐标对象上的 mock 标记改成 false。
     *
     * 接管模式下我们自己构造坐标，本来就没有这个标记；但如果目标应用走了别的读取路径，
     * 或者 NoReal 用的是普通模式（写系统定位源），这里的 hook 能把系统打上的标记擦掉。
     */
    private fun hookMockFlag(classLoader: ClassLoader) {
        val locationClass = runCatching {
            classLoader.loadClass("android.location.Location")
        }.getOrNull() ?: return
        XposedBridge.hookAllMethods(locationClass, "isMock", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = false
            }
        })
        XposedBridge.hookAllMethods(locationClass, "isFromMockProvider", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result = false
            }
        })
    }

    private fun hookLastKnownLocation(managerClass: Class<*>) {
        XposedBridge.hookAllMethods(managerClass, "getLastKnownLocation", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result as? Location ?: return
                param.result = FixSource.rewrite(result)
            }
        })
    }

    private fun hookCurrentLocation(managerClass: Class<*>) {
        XposedBridge.hookAllMethods(managerClass, "getCurrentLocation", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val index = args.indexOfFirst { it is Consumer<*> }
                if (index < 0) return
                val consumer = args[index] as? Consumer<Location> ?: return
                args[index] = Consumer<Location> { location -> consumer.accept(FixSource.rewrite(location)) }
            }
        })
    }

    private fun hookRequestLocationUpdates(managerClass: Class<*>) {
        XposedBridge.hookAllMethods(managerClass, "requestLocationUpdates", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args ?: return
                val index = args.indexOfFirst { it is LocationListener }
                if (index < 0) return
                args[index] = wrap(args[index] as LocationListener)
            }
        })
    }

    /** 用动态代理包一层回调：转发所有方法，只把 onLocationChanged 的坐标换掉。 */
    private fun wrap(listener: LocationListener): LocationListener {
        if (listener.javaClass.name.startsWith("com.noreal.locbridge.")) return listener
        val proxy = Proxy.newProxyInstance(
            listener.javaClass.classLoader,
            arrayOf(LocationListener::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "onLocationChanged" -> {
                    val location = args?.firstOrNull() as? Location
                    if (location != null) listener.onLocationChanged(FixSource.rewrite(location))
                    null
                }
                "onProviderEnabled" -> {
                    args?.firstOrNull()?.let { listener.onProviderEnabled(it as String) }
                    null
                }
                "onProviderDisabled" -> {
                    args?.firstOrNull()?.let { listener.onProviderDisabled(it as String) }
                    null
                }
                "toString" -> "LocBridgeListener(${listener.javaClass.name})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> (proxy === args?.firstOrNull())
                else -> null
            }
        }
        return proxy as LocationListener
    }
}
