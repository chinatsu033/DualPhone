/**
 * 云中继：WSS 连 Cloudflare Worker。
 * 文本=控制 JSON；二进制=DPAU 音频。
 * 「链路已开」≠「对端已入房」：看 relay_status.ready。
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
import org.json.JSONObject
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
    private val announceState: Boolean = true,
    private val onLinkChanged: ((connected: Boolean, peerReady: Boolean) -> Unit)? = null,
) {
    private val running = AtomicBoolean(false)
    private val linked = AtomicBoolean(false)
    private val peerReady = AtomicBoolean(false)
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
        .retryOnConnectionFailure(true)
        .build()

    /** 已与中继建立 WSS */
    fun isLinked(): Boolean = linked.get()

    /** 房间内双方都在（可互发业务消息） */
    fun isPeerReady(): Boolean = peerReady.get()

    /** 兼容旧调用：对端就绪才算真正连通 */
    fun isConnected(): Boolean = linked.get() && peerReady.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (roomCode.length != 6) {
            AppBus.setLastError("配对码必须是 6 位，当前「$roomCode」")
            AppBus.log("中继未启动：配对码无效「$roomCode」", "E")
            running.set(false)
            return
        }
        loopJob = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive && running.get()) {
                try {
                    if (announceState) {
                        AppBus.setConn(
                            if (backoff > 1000) ConnState.RECONNECTING else ConnState.CONNECTING,
                            "中继 $roomCode",
                        )
                    }
                    AppBus.log("中继连接中… room=$roomCode role=$role")
                    val opened = connectOnce()
                    if (!opened) throw IllegalStateException("WebSocket 握手失败")
                    backoff = 1000L
                    while (isActive && running.get() && socketRef.get() != null) {
                        delay(200)
                    }
                } catch (e: Exception) {
                    val m = e.message ?: e.javaClass.simpleName
                    AppBus.log("中继断开: $m", "W")
                    if (announceState) AppBus.setLastError("云中继: $m")
                } finally {
                    writerJob?.cancel()
                    socketRef.getAndSet(null)?.cancel()
                    val was = linked.getAndSet(false)
                    peerReady.set(false)
                    if (was) onLinkChanged?.invoke(false, false)
                    if (announceState) AppBus.setConn(ConnState.DISCONNECTED)
                }
                if (!running.get()) break
                if (announceState) AppBus.setConn(ConnState.RECONNECTING, "中继 $roomCode")
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
        val was = linked.getAndSet(false)
        peerReady.set(false)
        if (was) onLinkChanged?.invoke(false, false)
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

    fun sendAudioPcm(pcm: ByteArray) {
        val packet = ProtocolCodec.packAudio(seq.getAndIncrement(), pcm)
        audioSendQueue.offer(packet)
        while (audioSendQueue.size > 50) audioSendQueue.poll()
    }

    private suspend fun connectOnce(): Boolean {
        val base = relayBaseUrl.trim().trimEnd('/')
            .replaceFirst("^http://".toRegex(), "ws://")
            .replaceFirst("^https://".toRegex(), "wss://")
        if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
            AppBus.log("中继地址无效: $relayBaseUrl", "E")
            return false
        }
        val roleStr = if (role == DeviceRole.HOST) "host" else "companion"
        val url = "$base/ws?room=${roomCode.uppercase()}&role=$roleStr"
        val latchOk = AtomicBoolean(false)
        val latchDone = AtomicBoolean(false)
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef.set(webSocket)
                linked.set(true)
                peerReady.set(false)
                latchOk.set(true)
                latchDone.set(true)
                AppBus.clearLastError()
                AppBus.log("中继 WSS 已开，等待对端入房… $url")
                if (announceState) {
                    AppBus.setConn(ConnState.CONNECTING, "中继已开·等对端 $roomCode")
                }
                onLinkChanged?.invoke(true, false)
                enqueue(
                    Msg.Hello(
                        role = roleStr,
                        name = deviceName,
                    ),
                )
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
                if (text.contains("\"relay_status\"") || text.contains("\"relay_error\"")) {
                    handleRelayControl(text)
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
                val code = response?.code
                val detail = buildString {
                    append(t.message ?: t.javaClass.simpleName)
                    if (code != null) append(" HTTP $code")
                }
                AppBus.log("中继失败: $detail", "E")
                AppBus.setLastError("云中继: $detail")
                socketRef.compareAndSet(webSocket, null)
                latchDone.set(true)
            }
        }

        client.newWebSocket(request, listener)
        val deadline = System.currentTimeMillis() + 20_000
        while (!latchDone.get() && System.currentTimeMillis() < deadline && running.get()) {
            delay(50)
        }
        return latchOk.get() && socketRef.get() != null
    }

    private fun handleRelayControl(text: String) {
        AppBus.log("中继: $text")
        try {
            val obj = JSONObject(text)
            when (obj.optString("type")) {
                "relay_status" -> {
                    val ready = obj.optBoolean("ready", false)
                    val was = peerReady.getAndSet(ready)
                    if (ready && !was) {
                        AppBus.log("对端已入房，云中继就绪")
                        AppBus.clearLastError()
                        if (announceState) {
                            AppBus.setConn(ConnState.CONNECTED, "云中继 $roomCode")
                        }
                        onLinkChanged?.invoke(true, true)
                    } else if (!ready && was) {
                        AppBus.log("对端离开房间", "W")
                        if (announceState) {
                            AppBus.setConn(ConnState.CONNECTING, "中继已开·等对端 $roomCode")
                        }
                        onLinkChanged?.invoke(true, false)
                    } else if (!ready && announceState && linked.get()) {
                        AppBus.setConn(ConnState.CONNECTING, "中继已开·等对端 $roomCode")
                    }
                }
                "relay_error" -> {
                    AppBus.setLastError("云中继: ${obj.optString("error")}")
                }
            }
        } catch (_: Exception) {
        }
    }
}
