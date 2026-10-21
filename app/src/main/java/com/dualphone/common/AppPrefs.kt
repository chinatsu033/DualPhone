/**
 * 简单 SharedPreferences 封装：角色、对端 IP、功能开关。
 */
package com.dualphone.common

import android.content.Context
import android.content.SharedPreferences

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
}
