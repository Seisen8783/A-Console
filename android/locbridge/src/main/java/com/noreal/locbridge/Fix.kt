package com.noreal.locbridge

import android.os.SystemClock
import org.json.JSONObject

/** 一帧定位。协议与 NoReal 侧的本机广播保持一致。 */
internal data class Fix(
    val latitude: Double,
    val longitude: Double,
    val speed: Double,
    val bearing: Double,
    val accuracy: Double,
    val timeMillis: Long,
    val elapsedNanos: Long,
    val lap: Int
) {
    companion object {
        fun parse(text: String): Fix? = try {
            val json = JSONObject(text)
            Fix(
                latitude = json.getDouble("lat"),
                longitude = json.getDouble("lon"),
                speed = json.optDouble("spd", 0.0),
                bearing = json.optDouble("brg", 0.0),
                accuracy = json.optDouble("acc", 3.0),
                timeMillis = json.optLong("ts", System.currentTimeMillis()),
                elapsedNanos = json.optLong("ert", SystemClock.elapsedRealtimeNanos()),
                lap = json.optInt("lap", 1)
            )
        } catch (_: Exception) {
            null
        }
    }
}
