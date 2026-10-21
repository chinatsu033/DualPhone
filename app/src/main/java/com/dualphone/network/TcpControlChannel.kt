/**
 * TCP 控制通道：Host 作为 ServerSocket 监听，Companion 作为客户端连接。
 * 一行一条 JSON；断线后指数退避重连；发送队列在重连后尽力重放。
 */
package com.dualphone.network

import com.dualphone.common.AppBus
import com.dualphone.common.ConnState
import com.dualphone.common.DeviceRole
import com.dualphone.common.Msg
import com.dualphone.common.Ports
import com.dualphone.common.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class TcpControlChannel(
    private val scope: CoroutineScope,
    private val role: DeviceRole,
    private val deviceName: String,
    private val onMessage: (Msg) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val sendQueue = ConcurrentLinkedQueue<String>()
    private val replayQueue = ConcurrentLinkedQueue<String>()
    private val writeMutex = Mutex()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var serverSocket: ServerSocket? = null
    private var loopJob: Job? = null
    private var peerIp: String? = null

    fun start(manualPeerIp: String? = null) {
        if (!running.compareAndSet(false, true)) return
        peerIp = manualPeerIp?.takeIf { it.isNotBlank() }
        loopJob = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive && running.get()) {
                try {
                    AppBus.setConn(if (backoff > 1000) ConnState.RECONNECTING else ConnState.CONNECTING, peerIp)
                    AppBus.log("TCP 连接中… role=$role peer=$peerIp")
                    val sock = if (role == DeviceRole.HOST) acceptClient() else connectClient()
                    socket = sock
                    writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                    AppBus.setConn(ConnState.CONNECTED, sock.inetAddress?.hostAddress ?: peerIp)
                    AppBus.log("TCP 已连接 ${sock.remoteSocketAddress}")
                    backoff = 1000L
                    // hello + 重放队列
                    sendNow(
                        Msg.Hello(
                            role = if (role == DeviceRole.HOST) "host" else "companion",
                            name = deviceName,
                        ),
                    )
                    flushReplay()
                    // 读写循环
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    val writerJob = launch {
                        while (isActive && running.get() && sock.isConnected) {
                            val line = sendQueue.poll()
                            if (line != null) {
                                writeMutex.withLock {
                                    writer?.apply {
                                        write(line)
                                        newLine()
                                        flush()
                                    }
                                }
                            } else {
                                delay(20)
                            }
                        }
                    }
                    val pingJob = launch {
                        while (isActive && running.get() && sock.isConnected) {
                            delay(15_000)
                            enqueue(Msg.Ping())
                        }
                    }
                    try {
                        while (isActive && running.get()) {
                            val line = reader.readLine() ?: break
                            val msg = ProtocolCodec.decode(line) ?: continue
                            if (msg is Msg.Ping) enqueue(Msg.Pong())
                            onMessage(msg)
                            AppBus.emit(msg)
                        }
                    } finally {
                        writerJob.cancel()
                        pingJob.cancel()
                    }
                } catch (e: Exception) {
                    AppBus.log("TCP 断开: ${e.message}", "W")
                } finally {
                    closeQuietly()
                    AppBus.setConn(ConnState.DISCONNECTED)
                }
                if (!running.get()) break
                AppBus.setConn(ConnState.RECONNECTING)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        running.set(false)
        loopJob?.cancel()
        closeQuietly()
        AppBus.setConn(ConnState.DISCONNECTED)
    }

    /** 入队发送；重要消息同时进重放队列（断线后补发） */
    fun enqueue(msg: Msg, reliable: Boolean = false) {
        val line = ProtocolCodec.encode(msg)
        sendQueue.offer(line)
        if (reliable) {
            replayQueue.offer(line)
            while (replayQueue.size > 200) replayQueue.poll()
        }
    }

    private suspend fun sendNow(msg: Msg) {
        val line = ProtocolCodec.encode(msg)
        writeMutex.withLock {
            writer?.apply {
                write(line)
                newLine()
                flush()
            }
        }
    }

    private suspend fun flushReplay() {
        val items = mutableListOf<String>()
        while (true) {
            val x = replayQueue.poll() ?: break
            items.add(x)
        }
        writeMutex.withLock {
            val w = writer ?: return
            for (line in items) {
                w.write(line)
                w.newLine()
            }
            w.flush()
        }
        AppBus.log("重放 ${items.size} 条可靠消息")
    }

    private fun acceptClient(): Socket {
        serverSocket?.close()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(Ports.TCP_CONTROL))
        serverSocket = ss
        AppBus.log("Host 监听 TCP :${Ports.TCP_CONTROL}")
        ss.soTimeout = 60_000
        val sock = ss.accept()
        sock.tcpNoDelay = true
        peerIp = sock.inetAddress.hostAddress
        return sock
    }

    private fun connectClient(): Socket {
        val ip = peerIp ?: throw IllegalStateException("Companion 需要 peer IP")
        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(ip, Ports.TCP_CONTROL), 10_000)
        return sock
    }

    private fun closeQuietly() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
        serverSocket = null
    }
}
