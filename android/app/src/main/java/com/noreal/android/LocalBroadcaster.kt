package com.noreal.android

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 接管模式下的本机坐标广播。
 *
 * 只监听回环地址，流量不会离开这台手机，也不需要任何外部网络权限。
 * LocBridge 模块在目标进程（微信主进程、:appbrand 小程序逻辑层）里作为客户端订阅，
 * 拿到坐标后自己构造 Location 对象交给应用，因此不带系统打上的 mock 标记。
 */
internal class LocalBroadcaster(private val port: Int = DEFAULT_PORT) {

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val clients = CopyOnWriteArrayList<InetSocketAddress>()

    @Volatile
    var running = false
        private set

    @Volatile
    var clientCount: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start(): Boolean {
        if (running) return true
        return runCatching {
            val sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), port))
            socket = sock
            running = true
            thread = Thread({ loop(sock) }, "noreal-broadcast").apply {
                isDaemon = true
                start()
            }
            android.util.Log.i(TAG, "坐标广播已启动：$LOOPBACK:$port")
            true
        }.getOrElse {
            lastError = it.message ?: "无法监听 $LOOPBACK:$port"
            android.util.Log.e(TAG, "坐标广播启动失败：$lastError", it)
            false
        }
    }

    private fun loop(sock: DatagramSocket) {
        sock.soTimeout = 1000
        val buffer = ByteArray(1024)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (text.startsWith("SUB")) {
                    val address = packet.socketAddress as? InetSocketAddress ?: continue
                    if (!clients.contains(address)) clients.add(address)
                    clientCount = clients.size
                }
            } catch (_: SocketTimeoutException) {
                // 空闲轮询，顺便清理已经消失的订阅者。
            } catch (failure: Exception) {
                if (!running) break
                lastError = failure.message
            }
        }
        clients.clear()
        clientCount = 0
        runCatching { sock.close() }
    }

    fun publish(
        latitude: Double,
        longitude: Double,
        speed: Double,
        bearing: Double,
        accuracy: Double,
        timeMillis: Long,
        elapsedNanos: Long,
        lap: Int
    ) {
        val sock = socket ?: return
        val payload = JSONObject().apply {
            put("lat", latitude)
            put("lon", longitude)
            put("spd", speed)
            put("brg", bearing)
            put("acc", accuracy)
            put("ts", timeMillis)
            put("ert", elapsedNanos)
            put("lap", lap)
        }.toString().toByteArray(Charsets.UTF_8)
        for (client in clients) {
            runCatching { sock.send(DatagramPacket(payload, payload.size, client)) }
        }
    }

    fun stop() {
        running = false
        clients.clear()
        clientCount = 0
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    companion object {
        private const val TAG = "NoReal"
        const val DEFAULT_PORT = 38765
        private const val LOOPBACK = "127.0.0.1"
    }
}
