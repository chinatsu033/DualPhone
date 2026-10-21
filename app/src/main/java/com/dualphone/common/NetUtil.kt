/**
 * 本机局域网 IP、子网广播地址等工具。
 */
package com.dualphone.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NetUtil {
    /** 优先 Wi‑Fi / 热点的 IPv4；失败再扫网卡 */
    fun localIpv4(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            val lp: LinkProperties? = cm.getLinkProperties(network)
            if (caps != null && lp != null) {
                val hasLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (hasLan || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                    for (la in lp.linkAddresses) {
                        val a = la.address
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            return a.hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ip).array()
                return InetAddress.getByAddress(bytes).hostAddress
            }
        } catch (_: Exception) {
        }
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /** 根据本机 IPv4 猜测 /24 广播地址（常见家用路由） */
    fun subnetBroadcast(ipv4: String?): String? {
        if (ipv4.isNullOrBlank()) return null
        val parts = ipv4.split(".")
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.255"
    }

    fun isLikelyLan(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            false
        }
    }
}
