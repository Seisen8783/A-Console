package com.noreal.locbridge

import android.location.Location
import android.location.LocationManager
import java.util.concurrent.atomic.AtomicLong

/**
 * 当前生效的假坐标。
 *
 * 关键点：这里用的是 `new Location(...)` 新建对象，而不是系统 mock provider 写出来的对象。
 * 系统会给 mock provider 产出的每个坐标强制打上 isMock 标记，而新构造的 Location 天然没有这个标记，
 * 所以微信、小程序、腾讯位置服务都拦不住。
 */
internal object FixSource {

    @Volatile
    var fix: Fix? = null
        private set

    @Volatile
    var updatedAt: Long = 0L
        private set

    /** hook 命中次数，用来确认目标进程确实走进了被替换的读取路径。 */
    val hits = AtomicLong(0L)

    fun accept(next: Fix) {
        fix = next
        updatedAt = System.currentTimeMillis()
    }

    fun rewrite(original: Location): Location {
        val current = fix ?: return original
        hits.incrementAndGet()
        val out = Location(original.provider ?: LocationManager.GPS_PROVIDER)
        out.latitude = current.latitude
        out.longitude = current.longitude
        out.accuracy = current.accuracy.toFloat()
        out.speed = current.speed.toFloat()
        out.bearing = current.bearing.toFloat()
        out.time = current.timeMillis
        out.elapsedRealtimeNanos = current.elapsedNanos
        if (original.hasAltitude()) out.altitude = 0.0
        return out
    }
}
