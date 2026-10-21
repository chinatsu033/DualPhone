/**
 * 简单 SharedPreferences 封装：角色、对端 IP、连接模式、配对码、功能开关。
 */
package com.dualphone.common

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

class AppPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    var role: DeviceRole
        get() = if (sp.getString(Prefs.KEY_ROLE, "HOST") == "COMPANION") {
            DeviceRole.COMPANION
        } else {
            DeviceRole.HOST
        }
        set(v) = sp.edit().putString(Prefs.KEY_ROLE, v.name).apply()

    var peerIp: String
        get() = sp.getString(Prefs.KEY_PEER_IP, "") ?: ""
        set(v) = sp.edit().putString(Prefs.KEY_PEER_IP, v).apply()

    var deviceName: String
        get() = sp.getString(Prefs.KEY_DEVICE_NAME, android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(v) = sp.edit().putString(Prefs.KEY_DEVICE_NAME, v).apply()

    var enableSms: Boolean
        get() = sp.getBoolean(Prefs.KEY_SMS, true)
        set(v) = sp.edit().putBoolean(Prefs.KEY_SMS, v).apply()

    var enableCall: Boolean
        get() = sp.getBoolean(Prefs.KEY_CALL, true)
        set(v) = sp.edit().putBoolean(Prefs.KEY_CALL, v).apply()

    var enableNotif: Boolean
        get() = sp.getBoolean(Prefs.KEY_NOTIF, true)
        set(v) = sp.edit().putBoolean(Prefs.KEY_NOTIF, v).apply()

    var autoAnswer: Boolean
        get() = sp.getBoolean(Prefs.KEY_AUTO_ANSWER, false)
        set(v) = sp.edit().putBoolean(Prefs.KEY_AUTO_ANSWER, v).apply()

    var connMode: ConnMode
        get() = when (sp.getString(Prefs.KEY_CONN_MODE, "AUTO")) {
            "RELAY" -> ConnMode.RELAY
            "LAN" -> ConnMode.LAN
            else -> ConnMode.AUTO
        }
        set(v) = sp.edit().putString(Prefs.KEY_CONN_MODE, v.name).apply()

    var pairingCode: String
        get() = (sp.getString(Prefs.KEY_PAIRING_CODE, "") ?: "").uppercase()
        set(v) = sp.edit().putString(Prefs.KEY_PAIRING_CODE, v.uppercase().trim()).apply()

    var relayUrl: String
        get() {
            val v = sp.getString(Prefs.KEY_RELAY_URL, null)
            return if (v.isNullOrBlank()) RelayDefaults.DEFAULT_URL else v
        }
        set(v) = sp.edit().putString(Prefs.KEY_RELAY_URL, v.trim()).apply()

    /** 生成 6 位配对码（去掉易混字符） */
    fun generatePairingCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = buildString {
            repeat(6) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
        pairingCode = code
        return code
    }

    /** 确保有 6 位配对码（自动/中继模式需要） */
    fun ensurePairingCode(): String {
        if (pairingCode.length == 6) return pairingCode
        return generatePairingCode()
    }
}
