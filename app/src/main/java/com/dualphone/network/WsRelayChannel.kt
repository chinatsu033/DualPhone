/**
 * 云中继通道：经 WSS 连接 Cloudflare Worker / 自建 Node。
 * 文本帧 = 控制 JSON（与 TCP 相同）；二进制帧 = DPAU 音频包。
 * 配对码 6 位；断线指数退避重连；可靠消息入重放队列。
 */
package com.dualphone.network

import com.dualphone.common.AppBus
import com.dualphone.common.ConnState
import com.dualphone.common.DeviceRole
import com.dualphone.common.Msg
import com.dualphone.common.ProtocolCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WsRelayChannel(
    private val scope: CoroutineScope,
    private val role: DeviceRole,
    private val deviceName: String,
    private val relayBaseUrl: String,
    private val roomCode: String,
    private val onMessage: (Msg) -> Unit,
    private val onAudioFrame: ((ByteArray) -> Unit)? = null,
) {
    private val running = AtomicBoolean(false)
    private val sendQueue = ConcurrentLinkedQueue<String>()
    private val replayQueue = ConcurrentLinkedQueue<String>()
    private val audioSendQueue = ConcurrentLinkedQueue<ByteArray>()
    private val seq = AtomicInteger(0)
    private var loopJob: Job? = null
    private var writerJob: Job? = null
    private val socketRef = AtomicReference<WebSocket?>(null)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        loopJob = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive && running.get()) {
                try {
                    AppBus.setConn(
                        if (backoff > 1000) ConnState.RECONNECTING else ConnState.CONNECTING,
                        "relay:$roomCode",
                    )
                    AppBus.log("中继连接中… room=$roomCode role=$role")
                    val opened = connectOnce()
                    if (!opened) throw IllegalStateException("websocket open failed")
                    backoff = 1000L
                    // 保持到断开
                    while (isActive && running.get() && socketRef.get() != null) {
                        delay(200)
                    }
                } catch (e: Exception) {
                    AppBus.log("中继断开: ${e.message}", "W")
                } finally {
                    writerJob?.cancel()
                    socketRef.getAndSet(null)?.cancel()
                    AppBus.setConn(ConnState.DISCONNECTED)
                }
                if (!running.get()) break
                AppBus.setConn(ConnState.RECONNECTING, "relay:$roomCode")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun stop() {
        running.set(false)
        loopJob?.cancel()
        writerJob?.cancel()
        socketRef.getAndSet(null)?.close(1000, "stop")
        AppBus.setConn(ConnState.DISCONNECTED)
    }

    fun enqueue(msg: Msg, reliable: Boolean = false) {
        val line = ProtocolCodec.encode(msg)
        sendQueue.offer(line)
        if (reliable) {
            replayQueue.offer(line)
            while (replayQueue.size > 200) replayQueue.poll()
        }
    }

    /** 发送 PCM 音频帧（中继模式；Wi‑Fi 模式仍走 UDP） */
    fun sendAudioPcm(pcm: ByteArray) {
        val packet = ProtocolCodec.packAudio(seq.getAndIncrement(), pcm)
        audioSendQueue.offer(packet)
        while (audioSendQueue.size > 50) audioSendQueue.poll()
    }

    private suspend fun connectOnce(): Boolean {
        val base = relayBaseUrl.trim().trimEnd('/')
            .replaceFirst("^http://".toRegex(), "ws://")
            .replaceFirst("^https://".toRegex(), "wss://")
        val roleStr = if (role == DeviceRole.HOST) "host" else "companion"
        val url = "$base/ws?room=${roomCode.uppercase()}&role=$roleStr"
        val latchOk = AtomicBoolean(false)
        val latchDone = AtomicBoolean(false)
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef.set(webSocket)
                latchOk.set(true)
                latchDone.set(true)
                AppBus.setConn(ConnState.CONNECTED, "relay:$roomCode")
                AppBus.log("中继已连接 $url")
                enqueue(
                    Msg.Hello(
                        role = roleStr,
                        name = deviceName,
                    ),
                )
                // 重放
                val items = mutableListOf<String>()
                while (true) {
                    val x = replayQueue.poll() ?: break
                    items.add(x)
                }
                for (line in items) sendQueue.offer(line)
                AppBus.log("中继重放 ${items.size} 条")

                writerJob?.cancel()
                writerJob = scope.launch(Dispatchers.IO) {
                    while (isActive && running.get() && socketRef.get() === webSocket) {
                        var did = false
                        sendQueue.poll()?.let {
                            webSocket.send(it)
                            did = true
                        }
                        audioSendQueue.poll()?.let {
                            webSocket.send(it.toByteString())
                            did = true
                        }
                        if (!did) delay(15)
                    }
                }
                scope.launch(Dispatchers.IO) {
                    while (isActive && running.get() && socketRef.get() === webSocket) {
                        delay(15_000)
                        enqueue(Msg.Ping())
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 服务端状态
                if (text.contains("\"relay_status\"") || text.contains("\"relay_error\"")) {
                    AppBus.log("中继: $text")
                    return
                }
                val msg = ProtocolCodec.decode(text) ?: return
                if (msg is Msg.Ping) enqueue(Msg.Pong())
                onMessage(msg)
                AppBus.emit(msg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onAudioFrame?.invoke(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socketRef.compareAndSet(webSocket, null)) {
                    AppBus.log("中继关闭 code=$code $reason", "W")
                }
                latchDone.set(true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppBus.log("中继失败: ${t.message}", "E")
                socketRef.compareAndSet(webSocket, null)
                latchDone.set(true)
            }
        }

        client.newWebSocket(request, listener)
        // 等待 open 或失败（最多 20s）
        val deadline = System.currentTimeMillis() + 20_000
        while (!latchDone.get() && System.currentTimeMillis() < deadline && running.get()) {
            delay(50)
        }
        return latchOk.get() && socketRef.get() != null
    }
}
