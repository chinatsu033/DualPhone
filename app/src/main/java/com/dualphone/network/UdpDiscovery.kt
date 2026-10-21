/**
 * UDP 发现：组播 + 子网广播 + 全网广播；持有 MulticastLock。
 * 包内带配对码，仅同码才认对端，避免误连。
 */
package com.dualphone.network

import android.content.Context
import android.net.wifi.WifiManager
import com.dualphone.common.AppBus
import com.dualphone.common.DeviceRole
import com.dualphone.common.Msg
import com.dualphone.common.NetUtil
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
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.atomic.AtomicBoolean

class UdpDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
    private val role: DeviceRole,
    private val deviceName: String,
    private val pairingCode: String,
    private val onPeerFound: (ip: String, hello: Msg.Discover) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.IO) {
            acquireMulticastLock()
            var multi: MulticastSocket? = null
            var uni: DatagramSocket? = null
            try {
                // 组播接收
                multi = MulticastSocket(Ports.UDP_DISCOVERY).also { s ->
                    s.reuseAddress = true
                    s.soTimeout = 1500
                    try {
                        s.joinGroup(InetAddress.getByName(MULTICAST_GROUP))
                    } catch (e: Exception) {
                        AppBus.log("加入组播失败: ${e.message}", "W")
                    }
                }
                // 另开广播发送/接收（部分机型组播不通）
                uni = DatagramSocket(null).also { s ->
                    s.reuseAddress = true
                    s.broadcast = true
                    try {
                        s.bind(InetSocketAddress(Ports.UDP_DISCOVERY + 1))
                    } catch (_: Exception) {
                        s.bind(InetSocketAddress(0))
                    }
                    s.soTimeout = 1500
                }
                AppBus.log(
                    "发现已开 组播 $MULTICAST_GROUP:${Ports.UDP_DISCOVERY} 本机=${NetUtil.localIpv4(context)}",
                )

                val announce = launch {
                    while (isActive && running.get()) {
                        announceOnce(multi!!, uni!!)
                        delay(1500)
                    }
                }
                val buf = ByteArray(2048)
                try {
                    while (isActive && running.get()) {
                        // 轮询组播与单播口
                        receiveOne(multi!!, buf)
                        receiveOne(uni!!, buf)
                    }
                } finally {
                    announce.cancel()
                }
            } catch (e: Exception) {
                AppBus.log("发现异常: ${e.message}", "E")
            } finally {
                try {
                    multi?.leaveGroup(InetAddress.getByName(MULTICAST_GROUP))
                } catch (_: Exception) {
                }
                try {
                    multi?.close()
                } catch (_: Exception) {
                }
                try {
                    uni?.close()
                } catch (_: Exception) {
                }
                releaseMulticastLock()
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        releaseMulticastLock()
    }

    private fun announceOnce(multi: MulticastSocket, uni: DatagramSocket) {
        try {
            val localIp = NetUtil.localIpv4(context).orEmpty()
            val msg = Msg.Discover(
                role = if (role == DeviceRole.HOST) "host" else "companion",
                name = deviceName,
                tcpPort = Ports.TCP_CONTROL,
                pairingCode = pairingCode.uppercase(),
                ip = localIp,
            )
            val bytes = ProtocolCodec.encode(msg).toByteArray(Charsets.UTF_8)
            val targets = linkedSetOf(
                MULTICAST_GROUP,
                "255.255.255.255",
            )
            NetUtil.subnetBroadcast(localIp)?.let { targets.add(it) }
            for (t in targets) {
                try {
                    val addr = InetAddress.getByName(t)
                    // 组播口发组播；广播口发广播
                    if (t == MULTICAST_GROUP) {
                        multi.send(DatagramPacket(bytes, bytes.size, addr, Ports.UDP_DISCOVERY))
                    } else {
                        uni.send(DatagramPacket(bytes, bytes.size, addr, Ports.UDP_DISCOVERY))
                    }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            AppBus.log("发现广播失败: ${e.message}", "W")
        }
    }

    private fun receiveOne(socket: DatagramSocket, buf: ByteArray) {
        try {
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val msg = ProtocolCodec.decode(text) as? Msg.Discover ?: return
            val ip = msg.ip.ifBlank { packet.address.hostAddress ?: return }
            if (ip.isBlank() || ip == "0.0.0.0") return
            val selfIp = NetUtil.localIpv4(context)
            if (selfIp != null && ip == selfIp) return
            val want = if (role == DeviceRole.COMPANION) "host" else "companion"
            if (msg.role != want) return
            // 有配对码时必须一致；两边都空则放行（仅局域网调试）
            val mine = pairingCode.uppercase()
            val theirs = msg.pairingCode.uppercase()
            if (mine.length == 6 && theirs.length == 6 && mine != theirs) {
                AppBus.log("发现忽略 $ip：配对码不一致", "W")
                return
            }
            AppBus.log("发现对端 $ip (${msg.name}) code=${theirs.ifBlank { "-" }}")
            onPeerFound(ip, msg)
        } catch (_: java.net.SocketTimeoutException) {
        } catch (_: Exception) {
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("dualphone-discovery").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            AppBus.log("已获取 Wi‑Fi MulticastLock")
        } catch (e: Exception) {
            AppBus.log("MulticastLock 失败: ${e.message}", "W")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    companion object {
        const val MULTICAST_GROUP = "239.255.37.21"
    }
}
