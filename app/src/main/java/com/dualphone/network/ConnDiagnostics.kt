/**
 * 连接自检：本机 IP、UDP 发现、TCP、WSS 握手，结果写入 AppBus 日志。
 */
package com.dualphone.network

import android.content.Context
import com.dualphone.common.AppBus
import com.dualphone.common.AppPrefs
import com.dualphone.common.DeviceRole
import com.dualphone.common.NetUtil
import com.dualphone.common.Ports
import com.dualphone.common.RelayDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object ConnDiagnostics {
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = AppPrefs(context)
        val lines = mutableListOf<String>()
        fun L(s: String) {
            lines += s
            AppBus.log("自检: $s")
        }

        L("—— 连接自检开始 ——")
        val ip = NetUtil.localIpv4(context)
        L("本机 IPv4: ${ip ?: "未获取到（请连 Wi‑Fi / 热点）"}")
        L("是否 Wi‑Fi/有线: ${if (NetUtil.isLikelyLan(context)) "是" else "否（可能在用流量）"}")
        L("角色: ${if (prefs.role == DeviceRole.HOST) "转发端" else "接收端"}")
        L("配对码: ${prefs.pairingCode.ifBlank { "(空)" }}")
        L("中继 URL: ${prefs.relayUrl.ifBlank { RelayDefaults.DEFAULT_URL }}")
        L("对端 IP 缓存: ${prefs.peerIp.ifBlank { "(空)" }}")

        // UDP 发现口能否绑定
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(Ports.UDP_DISCOVERY))
                L("UDP 发现口 ${Ports.UDP_DISCOVERY}: 可绑定")
                val payload = "DPCHECK".toByteArray()
                val targets = listOfNotNull(
                    "255.255.255.255",
                    NetUtil.subnetBroadcast(ip),
                    UdpDiscovery.MULTICAST_GROUP,
                ).distinct()
                for (t in targets) {
                    try {
                        s.send(
                            DatagramPacket(
                                payload, payload.size,
                                InetAddress.getByName(t), Ports.UDP_DISCOVERY,
                            ),
                        )
                        L("UDP 探测已发 → $t:${Ports.UDP_DISCOVERY}")
                    } catch (e: Exception) {
                        L("UDP 探测失败 $t: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            L("UDP 发现口绑定失败: ${e.message}")
        }

        // TCP：转发端测监听；接收端测连对端
        if (prefs.role == DeviceRole.HOST) {
            try {
                java.net.ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(Ports.TCP_CONTROL))
                    L("TCP 控制口 ${Ports.TCP_CONTROL}: 可监听（本机 ${ip ?: "?"}:${Ports.TCP_CONTROL}）")
                }
            } catch (e: Exception) {
                L("TCP 控制口监听失败: ${e.message}")
            }
        } else {
            val peer = prefs.peerIp
            if (peer.isBlank()) {
                L("TCP: 接收端尚未缓存对端 IP（需同一 Wi‑Fi 自动发现，或手动填转发端 IP）")
            } else {
                try {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(peer, Ports.TCP_CONTROL), 5_000)
                        L("TCP 连接 $peer:${Ports.TCP_CONTROL}: 成功")
                    }
                } catch (e: Exception) {
                    L("TCP 连接 $peer:${Ports.TCP_CONTROL}: 失败 — ${e.message}")
                }
            }
        }

        // WSS
        val base = prefs.relayUrl.trim().ifBlank { RelayDefaults.DEFAULT_URL }
            .trimEnd('/')
            .replaceFirst("^http://".toRegex(), "ws://")
            .replaceFirst("^https://".toRegex(), "wss://")
        val code = prefs.pairingCode.ifBlank { "TEST00" }.uppercase()
        val role = if (prefs.role == DeviceRole.HOST) "host" else "companion"
        val url = "$base/ws?room=$code&role=$role"
        L("WSS 握手: $url")
        val result = probeWss(url)
        L("WSS 结果: $result")

        L("—— 自检结束 ——")
        if (prefs.pairingCode.length != 6) {
            L("提示: 两边必须填同一个 6 位配对码，否则云中继永远对不上。")
        }
        lines.joinToString("\n")
    }

    private suspend fun probeWss(url: String): String = coroutineScope {
        val ok = AtomicBoolean(false)
        val err = AtomicReference<String?>(null)
        val done = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder().url(url).build()
        val ws = client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    ok.set(true)
                    done.set(true)
                    webSocket.close(1000, "diag")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    err.set(t.message ?: "unknown")
                    done.set(true)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.set(true)
                }
            },
        )
        val deadline = System.currentTimeMillis() + 15_000
        while (!done.get() && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        if (!done.get()) {
            ws.cancel()
            return@coroutineScope "超时（15s）"
        }
        if (ok.get()) "握手成功" else "失败: ${err.get() ?: "未知"}"
    }
}
