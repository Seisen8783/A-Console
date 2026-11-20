package com.noreal.locbridge.probe

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 定位探针。
 *
 * 做两件事：
 * 1. 读系统定位，把坐标、provider、isMock 标记打出来——用来判断目标应用能不能读到干净坐标。
 * 2. 订阅本机坐标流（NoReal 接管模式），把收到的假坐标打出来——用来判断注入链路是否通。
 *
 * 它同时把自己加进 LocBridge 的目标列表，装了 LSPosed 之后它自己就是被注入对象，
 * 于是「探针读到的坐标」直接就是注入效果的真值。
 */
class LocProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val streamFix = AtomicReference<String?>(null)
    private val systemFix = AtomicReference<String?>(null)
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerAsTarget()
        subscribeStream()
        requestSystemFixes()
        view = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 13f
            setTextIsSelectable(true)
        }
        setContentView(view)
        handler.post(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
    /** 把探针自己加进 LocBridge 的目标列表。 */
    private fun registerAsTarget() {
        runCatching {
            contentResolver.insert(
                Uri.parse("content://$BRIDGE_AUTHORITY"),
                ContentValues().apply { put("targets", packageName) }
            )
        }.onFailure { Log.i(TAG, "LocBridge 未安装，跳过目标注册") }
    }

    /** 订阅 NoReal 的本机坐标流。 */
    private fun subscribeStream() {
        Thread({
            val target = InetSocketAddress("127.0.0.1", PORT)
            while (!Thread.currentThread().isInterrupted) {
                var sock: DatagramSocket? = null
                try {
                    sock = DatagramSocket()
                    sock.soTimeout = 2000
                    // 不 connect：connect 过的 UDP socket 在对端没监听时会收到
                    // ICMP port-unreachable，之后收发全部失败，订阅线程就此死掉。
                    sock.send(DatagramPacket(SUB, SUB.size, target))
                    val buffer = ByteArray(4096)
                    while (!Thread.currentThread().isInterrupted) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            sock.receive(packet)
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val json = JSONObject(text)
                            streamFix.set(
                                "lat=%.6f lon=%.6f spd=%.2f brg=%.1f lap=%d ts=%d".format(
                                    json.optDouble("lat"),
                                    json.optDouble("lon"),
                                    json.optDouble("spd"),
                                    json.optDouble("brg"),
                                    json.optInt("lap"),
                                    json.optLong("ts")
                                )
                            )
                            Log.i(TAG, "stream ${streamFix.get()}")
                        } catch (_: SocketTimeoutException) {
                            // UDP 不会重传之前的订阅包，这里定期补发一个，
                            // 保证 NoReal 后启动时也能发现我们。
                            runCatching { sock.send(DatagramPacket(SUB, SUB.size, target)) }
                        }
                    }
                } catch (failure: Exception) {
                    Log.w(TAG, "订阅断开，2 秒后重连：${failure.message}")
                    runCatching { Thread.sleep(2000L) }
                } finally {
                    runCatching { sock?.close() }
                }
            }
        }, "locprobe-stream").apply { isDaemon = true; start() }
    }

    @SuppressLint("MissingPermission")
    private fun requestSystemFixes() {
        runCatching {
            val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { publish(it, "lastKnown") }
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) = publish(location, "update")
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                },
                Looper.getMainLooper()
            )
        }.onFailure { systemFix.set("读取系统定位失败：${it.message}") }
    }

    private fun publish(location: Location, source: String) {
        val mocked = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider
        systemFix.set(
            "[$source] provider=${location.provider} lat=%.6f lon=%.6f acc=%.1f spd=%.2f brg=%.1f isMock=%s".format(
                location.latitude,
                location.longitude,
                location.accuracy,
                location.speed,
                location.bearing,
                mocked
            )
        )
        Log.i(TAG, "system $source provider=${location.provider} lat=${location.latitude} lon=${location.longitude} isMock=$mocked")
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (view != null) {
                view.text = buildString {
                    append("探针 · com.noreal.locbridge.probe\n\n")
                    append("【系统定位】\n").append(systemFix.get() ?: "等待中…").append("\n\n")
                    append("【本机坐标流】\n").append(streamFix.get() ?: "等待 NoReal 接管模式…")
                }
            }
            handler.postDelayed(this, 500L)
        }
    }

    companion object {
        private const val TAG = "LocProbe"
        private const val PORT = 38765
        private const val BRIDGE_AUTHORITY = "com.noreal.locbridge.provider"
        private val SUB = "SUB".toByteArray()
    }
}
