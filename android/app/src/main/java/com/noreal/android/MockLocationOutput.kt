package com.noreal.android

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.noreal.core.Point
import java.util.concurrent.TimeUnit

// The runtime grant is the mock-location app op, rather than a runtime permission dialog.
@SuppressLint("MissingPermission", "ApplySharedPref")
internal class MockLocationOutput(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val preferences = context.getSharedPreferences("location_output", Context.MODE_PRIVATE)
    private var fusedClient: FusedLocationProviderClient? = null
    private val providers = linkedSetOf<String>()
    val activeSources: List<String> get() = providers.toList() + listOfNotNull(fusedClient?.let { "Google FLP" })

    fun start() {
        // 不在这里前置检查 app op：兼容层（鸿蒙卓易通等）的 checkOpNoThrow 可能误报，
        // 让 addTestProvider 的真实结果决定成败。
        check(restorePending()) { "上一会话的定位输出尚未清理。" }
        register(LocationManager.GPS_PROVIDER)
        // 「仅设备」模式下 network 本来是被禁用的。一旦接管它并置为 enabled，
        // 系统会重新用网络定位，把刚关掉的 WiFi/基站路径又打开，所以只在允许网络定位时才接管。
        if (networkAllowed()) {
            val hasNetwork = LocationManager.NETWORK_PROVIDER in manager.allProviders
            if (hasNetwork) {
                runCatching { register(LocationManager.NETWORK_PROVIDER) }
            }
        }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            val client = LocationServices.getFusedLocationProviderClient(context)
            check(preferences.edit().putBoolean(KEY_FUSED, true).commit()) { "无法保存融合定位恢复状态。" }
            if (runCatching { Tasks.await(client.setMockMode(true), 3, TimeUnit.SECONDS) }.isSuccess) {
                fusedClient = client
            } else {
                clearFused()
            }
        }
    }

    /** 当前系统定位模式是否允许网络定位介入。 */
    private fun networkAllowed(): Boolean {
        val mode = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
        )
        return mode != Settings.Secure.LOCATION_MODE_SENSORS_ONLY
    }

    @Suppress("DEPRECATION")
    private fun register(name: String) {
        val gps = name == LocationManager.GPS_PROVIDER
        check(preferences.edit().putStringSet(KEY_PROVIDERS, pendingProviders() + name).commit()) {
            "无法保存测试定位恢复状态。"
        }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                manager.addTestProvider(name, ProviderProperties.Builder()
                    .setHasSatelliteRequirement(gps)
                    .setHasNetworkRequirement(!gps)
                    .setHasCellRequirement(!gps)
                    .setHasAltitudeSupport(false)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build())
            } else {
                manager.addTestProvider(
                    name, !gps, gps, !gps, false, false, true, true,
                    ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
                )
            }
            manager.setTestProviderEnabled(name, true)
            providers.add(name)
        } catch (failure: Exception) {
            clearProvider(name)
            throw failure
        }
    }

    fun publish(point: Point, speed: Double, bearing: Double, epochMillis: Long, monotonicNanos: Long) {
        check(allowed(context)) { "模拟定位授权已失效。" }
        fun sample(provider: String) = Location(provider).apply {
            latitude = point.latitude
            longitude = point.longitude
            accuracy = 1f
            this.speed = speed.toFloat()
            this.bearing = bearing.toFloat()
            time = epochMillis
            elapsedRealtimeNanos = monotonicNanos
        }
        for (name in providers.toList()) {
            if (name == LocationManager.GPS_PROVIDER) {
                manager.setTestProviderLocation(name, sample(name))
            } else if (runCatching { manager.setTestProviderLocation(name, sample(name)) }.isFailure) {
                clearProvider(name)
            }
        }
        fusedClient?.let { client ->
            val result = runCatching { Tasks.await(client.setMockLocation(sample("fused")), 1, TimeUnit.SECONDS) }
            if (result.isFailure) clearFused()
        }
    }

    fun restorePending(): Boolean {
        pendingProviders().forEach(::clearProvider)
        if (preferences.getBoolean(KEY_FUSED, false)) clearFused()
        preferences.edit().remove(KEY_HOLDING).commit()
        return !hasPending(context)
    }

    /** 模拟正常结束：保留已注册的测试定位源，让定位停在最后一点。 */
    fun markHeld(): Boolean {
        preferences.edit().putBoolean(KEY_HOLDING, true).commit()
        return pendingProviders().isNotEmpty() || preferences.getBoolean(KEY_FUSED, false)
    }

    private fun clearProvider(name: String) {
        providers.remove(name)
        if (runCatching { manager.removeTestProvider(name) }.isSuccess) {
            preferences.edit().putStringSet(KEY_PROVIDERS, pendingProviders() - name).commit()
        }
    }

    private fun clearFused() {
        val client = fusedClient
        fusedClient = null
        if (runCatching {
                val existing = client ?: LocationServices.getFusedLocationProviderClient(context)
                Tasks.await(existing.setMockMode(false), 3, TimeUnit.SECONDS)
            }.isSuccess) {
            preferences.edit().remove(KEY_FUSED).commit()
        }
    }

    private fun pendingProviders(): Set<String> = preferences.getStringSet(KEY_PROVIDERS, emptySet())?.toSet().orEmpty()

    companion object {
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_FUSED = "fused"
        private const val KEY_HOLDING = "holding"

        /**
         * 是否还有需要清理的残留。被标记为 holding 的是正常结束后主动保留的，
         * 不算残留，启动时不应该自动清掉。
         */
        fun hasPending(context: Context): Boolean {
            val preferences = context.getSharedPreferences("location_output", Context.MODE_PRIVATE)
            if (preferences.getBoolean(KEY_HOLDING, false)) return false
            return preferences.getStringSet(KEY_PROVIDERS, emptySet())?.isNotEmpty() == true ||
                preferences.getBoolean(KEY_FUSED, false)
        }

        /** 上次模拟结束后是否仍把定位保持在终点。 */
        fun isHolding(context: Context): Boolean =
            context.getSharedPreferences("location_output", Context.MODE_PRIVATE)
                .getBoolean(KEY_HOLDING, false)

        @Suppress("DEPRECATION")
        fun allowed(context: Context): Boolean {
            val manager = context.getSystemService(AppOpsManager::class.java)
            return manager.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }
    }
}
