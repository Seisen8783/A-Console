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

    private var socket: DatagramSocket? = null
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
            val sock = DatagramSocket()
            socket = sock
            sock.soTimeout = 2000
            val server = InetSocketAddress(LOOPBACK, port)
            try {
                sock.connect(server)
                subscribe(sock)
            } catch (failure: Exception) {
                lastError.set(failure.message)
            }
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
                    // NoReal 空闲时不发包，用订阅包做一次保活，顺便探测服务是否还在。
                    runCatching { subscribe(sock) }
                } catch (failure: Exception) {
                    if (!running) break
                    lastError.set(failure.message)
                    connected = false
                    runCatching { Thread.sleep(1000L) }
                    runCatching {
                        sock.connect(InetSocketAddress(LOOPBACK, port))
                        subscribe(sock)
                    }
                }
            }
            runCatching { sock.close() }
        }, "noreal-bridge-stream").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        thread = null
        connected = false
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private val SUBSCRIBE = "SUB".toByteArray()

        private fun subscribe(socket: DatagramSocket) {
            socket.send(DatagramPacket(SUBSCRIBE, SUBSCRIBE.size))
        }
    }
}
