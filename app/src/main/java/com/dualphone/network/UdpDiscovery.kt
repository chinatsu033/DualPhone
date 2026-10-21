/**
 * UDP 广播发现：同一 Wi‑Fi 下互相广播 discover 包，Companion 可自动填入 Host IP。
 */
package com.dualphone.network

import com.dualphone.common.AppBus
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class UdpDiscovery(
    private val scope: CoroutineScope,
    private val role: DeviceRole,
    private val deviceName: String,
    private val onPeerFound: (ip: String, hello: Msg.Discover) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        AppBus.setConn(com.dualphone.common.ConnState.DISCOVERING)
        job = scope.launch(Dispatchers.IO) {
            DatagramSocket(Ports.UDP_DISCOVERY).use { socket ->
                socket.broadcast = true
                socket.soTimeout = 2000
                val buf = ByteArray(2048)
                val announceJob = launch {
                    while (isActive && running.get()) {
                        try {
                            val msg = Msg.Discover(
                                role = if (role == DeviceRole.HOST) "host" else "companion",
                                name = deviceName,
                            )
                            val bytes = ProtocolCodec.encode(msg).toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(
                                bytes,
                                bytes.size,
                                InetAddress.getByName("255.255.255.255"),
                                Ports.UDP_DISCOVERY,
                            )
                            socket.send(packet)
                        } catch (e: Exception) {
                            AppBus.log("发现广播失败: ${e.message}", "W")
                        }
                        delay(2000)
                    }
                }
                try {
                    while (isActive && running.get()) {
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            socket.receive(packet)
                            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            val msg = ProtocolCodec.decode(text) as? Msg.Discover ?: continue
                            val ip = packet.address.hostAddress ?: continue
                            // 忽略自己角色相同的包；Companion 找 Host，Host 也可记录 Companion
                            val want = if (role == DeviceRole.COMPANION) "host" else "companion"
                            if (msg.role == want) {
                                AppBus.log("发现对端 $ip (${msg.name})")
                                onPeerFound(ip, msg)
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // continue
                        }
                    }
                } finally {
                    announceJob.cancel()
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
    }
}
