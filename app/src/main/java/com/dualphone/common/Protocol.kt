/**
 * DualPhone 协议定义：TCP 控制通道使用「一行一条 JSON」；
 * UDP 音频通道使用固定头 + PCM16LE mono 16kHz 帧。
 */
package com.dualphone.common

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** 设备角色：Host=手机 A（有 SIM），Companion=手机 B（镜像端） */
enum class DeviceRole { HOST, COMPANION }

/** 连接方式：局域网 TCP/UDP，或云中继 WSS（流量可用） */
enum class ConnMode { LAN, RELAY }

object RelayDefaults {
    /** 部署后的默认 Worker 基址（可在设置里改） */
    const val DEFAULT_URL = ""  // 部署 relay/ 后填入 https://dualphone-relay.<subdomain>.workers.dev
}

object Ports {
    const val TCP_CONTROL = 37210
    const val UDP_AUDIO = 37211
    const val UDP_DISCOVERY = 37212
}

object AudioFormat {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS = 16
    /** 每帧采样数（约 20ms @16kHz） */
    const val FRAME_SAMPLES = 320
    const val FRAME_BYTES = FRAME_SAMPLES * 2
    const val MAGIC: Int = 0x44504155 // "DPAU"
    const val HEADER_SIZE = 12 // magic(4) + seq(4) + len(4)
}

/** 协议消息基类字段 */
sealed class Msg {
    abstract val type: String

    data class Hello(
        val role: String,
        val name: String,
        val port: Int = Ports.TCP_CONTROL,
        override val type: String = "hello",
    ) : Msg()

    data class Ping(override val type: String = "ping", val ts: Long = System.currentTimeMillis()) : Msg()
    data class Pong(override val type: String = "pong", val ts: Long = System.currentTimeMillis()) : Msg()

    data class SmsIn(
        val id: String = UUID.randomUUID().toString(),
        val from: String,
        val body: String,
        val ts: Long = System.currentTimeMillis(),
        override val type: String = "sms_in",
    ) : Msg()

    data class SmsOut(
        val id: String = UUID.randomUUID().toString(),
        val to: String,
        val body: String,
        override val type: String = "sms_out",
    ) : Msg()

    data class SmsAck(
        val id: String,
        val ok: Boolean,
        val error: String? = null,
        override val type: String = "sms_ack",
    ) : Msg()

    data class CallRinging(
        val number: String,
        val name: String? = null,
        override val type: String = "call_ringing",
    ) : Msg()

    data class CallAnswer(override val type: String = "call_answer") : Msg()
    data class CallHangup(override val type: String = "call_hangup") : Msg()

    data class CallState(
        val state: String,
        val number: String? = null,
        override val type: String = "call_state",
    ) : Msg()

    data class Notif(
        val id: String,
        val pkg: String,
        val title: String?,
        val text: String?,
        val groupKey: String? = null,
        val priority: Int = 0,
        val ts: Long = System.currentTimeMillis(),
        override val type: String = "notif",
    ) : Msg()

    data class NotifRemoved(
        val id: String,
        override val type: String = "notif_removed",
    ) : Msg()

    data class LogLine(
        val level: String,
        val text: String,
        override val type: String = "log",
    ) : Msg()

    data class Discover(
        val role: String,
        val name: String,
        val tcpPort: Int = Ports.TCP_CONTROL,
        override val type: String = "discover",
    ) : Msg()
}

object ProtocolCodec {
    val gson: Gson = Gson()

    fun encode(msg: Msg): String = gson.toJson(msg)

    /**
     * 解析一行 JSON。未知 type 返回 null（忽略）。
     */
    fun decode(line: String): Msg? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val obj = JsonParser.parseString(trimmed).asJsonObject
            when (obj.get("type")?.asString) {
                "hello" -> gson.fromJson(obj, Msg.Hello::class.java)
                "ping" -> gson.fromJson(obj, Msg.Ping::class.java)
                "pong" -> gson.fromJson(obj, Msg.Pong::class.java)
                "sms_in" -> gson.fromJson(obj, Msg.SmsIn::class.java)
                "sms_out" -> gson.fromJson(obj, Msg.SmsOut::class.java)
                "sms_ack" -> gson.fromJson(obj, Msg.SmsAck::class.java)
                "call_ringing" -> gson.fromJson(obj, Msg.CallRinging::class.java)
                "call_answer" -> gson.fromJson(obj, Msg.CallAnswer::class.java)
                "call_hangup" -> gson.fromJson(obj, Msg.CallHangup::class.java)
                "call_state" -> gson.fromJson(obj, Msg.CallState::class.java)
                "notif" -> gson.fromJson(obj, Msg.Notif::class.java)
                "notif_removed" -> gson.fromJson(obj, Msg.NotifRemoved::class.java)
                "log" -> gson.fromJson(obj, Msg.LogLine::class.java)
                "discover" -> gson.fromJson(obj, Msg.Discover::class.java)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 打包 UDP 音频帧 */
    fun packAudio(seq: Int, pcm: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(AudioFormat.HEADER_SIZE + pcm.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(AudioFormat.MAGIC)
        buf.putInt(seq)
        buf.putInt(pcm.size)
        buf.put(pcm)
        return buf.array()
    }

    /** 解包 UDP 音频帧；非法返回 null */
    fun unpackAudio(data: ByteArray): Pair<Int, ByteArray>? {
        if (data.size < AudioFormat.HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val magic = buf.int
        if (magic != AudioFormat.MAGIC) return null
        val seq = buf.int
        val len = buf.int
        if (len < 0 || AudioFormat.HEADER_SIZE + len > data.size) return null
        val pcm = ByteArray(len)
        buf.get(pcm)
        return seq to pcm
    }
}

/** 连接状态 */
enum class ConnState { DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED, RECONNECTING }

/** 应用偏好键 */
object Prefs {
    const val NAME = "dualphone"
    const val KEY_ROLE = "role"
    const val KEY_PEER_IP = "peer_ip"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_SMS = "enable_sms"
    const val KEY_CALL = "enable_call"
    const val KEY_NOTIF = "enable_notif"
    const val KEY_AUTO_ANSWER = "auto_answer"
    const val KEY_CONN_MODE = "conn_mode"
    const val KEY_PAIRING_CODE = "pairing_code"
    const val KEY_RELAY_URL = "relay_url"
}
