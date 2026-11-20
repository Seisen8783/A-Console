package com.noreal.locbridge

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 从本机 NoReal 进程订阅坐标流。
 *
 * NoReal 在接管模式下会监听 127.0.0.1:port，把每秒一帧的坐标以 UDP 广播给订阅者。
 * 走回环地址意味着流量不会离开这台手机，也不需要任何外部网络权限。
 */
internal class StreamClient(private val port: Int) {

    private var thread: Thread? = null

    @Volatile
    var running = false

    val lastError = AtomicReference<String?>(null)

    /** 是否已经收到过坐标。 */
    @Volatile
    var connected = false

    fun start(onFix: (Fix) -> Unit) {
        if (running) return
        running = true
        thread = Thread({
            val server = InetSocketAddress(LOOPBACK, port)
            while (running) {
                var sock: DatagramSocket? = null
                try {
                    sock = DatagramSocket()
                    sock.soTimeout = 2000
                    // 不 connect：对端没在监听时，connect 过的 UDP socket 会收到
                    // ICMP port-unreachable，之后收发全部失败。模块通常比 NoReal 先启动，
                    // 这里保持无连接，靠定期重发订阅包维持关系。
                    sock.send(DatagramPacket(SUBSCRIBE, SUBSCRIBE.size, server))
                    val buffer = ByteArray(4096)
                    while (running) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            sock.receive(packet)
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val fix = Fix.parse(text)
                            if (fix != null) {
                                connected = true
                                lastError.set(null)
                                onFix(fix)
                            }
                        } catch (_: SocketTimeoutException) {
                            runCatching { sock.send(DatagramPacket(SUBSCRIBE, SUBSCRIBE.size, server)) }
                        }
                    }
                } catch (failure: Exception) {
                    if (!running) break
                    lastError.set(failure.message)
                    connected = false
                    runCatching { Thread.sleep(1000L) }
                } finally {
                    runCatching { sock?.close() }
                }
            }
            connected = false
        }, "noreal-bridge-stream").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread = null
        connected = false
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private val SUBSCRIBE = "SUB".toByteArray()
    }
}
