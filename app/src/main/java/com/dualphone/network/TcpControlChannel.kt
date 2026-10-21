/**
 * TCP 控制通道：转发端 ServerSocket 监听，接收端连接。
 * 接收端若尚无对端 IP，会等待 discovery / 手动填写，不再立刻抛错退出。
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
    private val announceState: Boolean = true,
    private val onLinkChanged: ((connected: Boolean, peer: String?) -> Unit)? = null,
) {
    private val running = AtomicBoolean(false)
    private val linked = AtomicBoolean(false)
    private val sendQueue = ConcurrentLinkedQueue<String>()
    private val replayQueue = ConcurrentLinkedQueue<String>()
    private val writeMutex = Mutex()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var serverSocket: ServerSocket? = null
    private var loopJob: Job? = null
    @Volatile private var peerIp: String? = null

    fun isConnected(): Boolean = linked.get()

    fun currentPeer(): String? = peerIp

    fun setPeerIp(ip: String) {
        if (ip.isBlank()) return
        peerIp = ip
        AppBus.log("TCP 对端 IP → $ip")
    }

    fun start(manualPeerIp: String? = null) {
        if (!running.compareAndSet(false, true)) return
        if (!manualPeerIp.isNullOrBlank()) peerIp = manualPeerIp
        loopJob = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive && running.get()) {
                try {
                    if (announceState) {
                        AppBus.setConn(
                            if (backoff > 1000) ConnState.RECONNECTING else ConnState.CONNECTING,
                            peerIp,
                        )
                    }
                    AppBus.log("TCP 连接中… role=$role peer=$peerIp")
                    val sock = if (role == DeviceRole.HOST) {
                        acceptClient()
                    } else {
                        waitAndConnectClient()
                    }
                    socket = sock
                    writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                    linked.set(true)
                    val peerAddr = sock.inetAddress?.hostAddress ?: peerIp
                    peerIp = peerAddr
                    if (announceState) {
                        AppBus.setConn(ConnState.CONNECTED, "局域网 $peerAddr")
                    }
                    AppBus.clearLastError()
                    AppBus.log("TCP 已连接 ${sock.remoteSocketAddress}")
                    onLinkChanged?.invoke(true, peerAddr)
                    backoff = 1000L
                    sendNow(
                        Msg.Hello(
                            role = if (role == DeviceRole.HOST) "host" else "companion",
                            name = deviceName,
                        ),
                    )
                    flushReplay()
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
                    val m = e.message ?: e.javaClass.simpleName
                    AppBus.log("TCP 断开: $m", "W")
                    if (announceState) AppBus.setLastError("局域网: $m")
                } finally {
                    val was = linked.getAndSet(false)
                    closeQuietly()
                    if (was) onLinkChanged?.invoke(false, peerIp)
                    if (announceState) AppBus.setConn(ConnState.DISCONNECTED)
                }
                if (!running.get()) break
                if (announceState) AppBus.setConn(ConnState.RECONNECTING)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    fun stop() {
        running.set(false)
        loopJob?.cancel()
        val was = linked.getAndSet(false)
        closeQuietly()
        if (was) onLinkChanged?.invoke(false, peerIp)
        if (announceState) AppBus.setConn(ConnState.DISCONNECTED)
    }

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
        AppBus.log("转发端监听 TCP :${Ports.TCP_CONTROL}")
        // 较短超时以便 stop() 时能退出；外层循环会重试
        ss.soTimeout = 30_000
        val sock = ss.accept()
        sock.tcpNoDelay = true
        sock.keepAlive = true
        peerIp = sock.inetAddress.hostAddress
        return sock
    }

    /** 接收端：没有 IP 就等，有了再连；避免空 IP 立刻抛错把整个通道打成失败 */
    private suspend fun waitAndConnectClient(): Socket {
        while (running.get()) {
            val ip = peerIp
            if (!ip.isNullOrBlank()) {
                AppBus.log("接收端连接 $ip:${Ports.TCP_CONTROL}")
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.connect(InetSocketAddress(ip, Ports.TCP_CONTROL), 8_000)
                return sock
            }
            if (announceState) AppBus.setConn(ConnState.DISCOVERING)
            AppBus.log("接收端等待发现转发端 IP…")
            delay(800)
        }
        throw IllegalStateException("已停止")
    }

    private fun closeQuietly() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        writer = null
        socket = null
        serverSocket = null
    }
}
