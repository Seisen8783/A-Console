package com.unreal.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把轨迹展开成 GPX。
 *
 * 采样规则和回放时一致：按配速每秒一个点，多个圈把轨迹重复并让时间连续递增。
 * 消费端是 iOS 的开发者服务（pymobiledevice3）或 SDR 的 gps-sdr-sim，
 * 它们都按 <time> 字段回放，所以间隔决定移动速度、必须连续且单调递增。
 */
object GpxWriter {

    /** 单文件点数上限：太大会拖慢回放，也容易被消费端拒绝。 */
    const val MAX_POINTS = 20_000

    data class Options(
        val paceSecondsPerKm: Double = 360.0,
        val laps: Int = 1,
        val startEpochMillis: Long = System.currentTimeMillis(),
        val name: String = "UnReal",
        /** 轨迹首尾落差超过这个值时标注出来（循环处会跳变）。 */
        val loopGapWarningMeters: Double = 30.0
    )

    data class Result(val xml: String, val points: Int, val seconds: Double, val stepSeconds: Double)

    fun build(route: Route, options: Options): Result {
        require(route.lengthMeters > 0.0) { "轨迹长度为零" }
        val pace = options.paceSecondsPerKm.takeIf { it > 0.0 } ?: 360.0
        val laps = options.laps.coerceAtLeast(1)
        val lapSeconds = route.durationSeconds(pace)
        require(lapSeconds.isFinite() && lapSeconds > 0.0) { "配速不合法" }

        // 1 秒一个点；超过上限时自动降采样，保证不吃爆消费端。
        val rawPoints = lapSeconds * laps
        val step = if (rawPoints > MAX_POINTS) rawPoints / MAX_POINTS else 1.0
        val total = lapSeconds * laps

        val builder = StringBuilder(64 * 1024)
        builder.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        builder.append("""<gpx version="1.1" creator="UnReal" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        builder.append("  <metadata><name>").append(escape(options.name)).append("</name></metadata>\n")
        builder.append("  <trk><name>").append(escape(options.name)).append("</name><trkseg>\n")

        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        var elapsed = 0.0
        var count = 0
        while (elapsed <= total || count == 0) {
            val withinLap = if (lapSeconds <= 0.0) 0.0 else elapsed % lapSeconds
            val sample = route.sample(withinLap, lapSeconds)
            val time = options.startEpochMillis + (elapsed * 1000.0).toLong()
            builder.append("""    <trkpt lat="""")
                .append(formatCoordinate(sample.point.latitude))
                .append("""\" lon="""")
                .append(formatCoordinate(sample.point.longitude))
                .append("""">""")
                .append("<ele>0</ele><time>").append(format.format(Date(time))).append("</time></trkpt>\n")
            count++
            if (total > 0.0 && elapsed >= total) break
            elapsed += step
            if (elapsed > total) elapsed = total
        }

        builder.append("  </trkseg></trk>\n</gpx>\n")
        return Result(builder.toString(), count, total, step)
    }

    /** 首尾距离，用来判断循环处会不会跳变。 */
    fun loopGapMeters(route: Route): Double =
        if (route.points.size < 2) 0.0 else distanceMeters(route.points.first(), route.points.last())

    private fun formatCoordinate(value: Double): String =
        String.format(Locale.US, "%.6f", value)

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
