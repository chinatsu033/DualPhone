/**
 * Host 来电检测：优先 TelephonyCallback（API 31+），否则 PhoneStateListener。
 * 检测到 RINGING 后通过 TCP 通知 Companion；可选自动接听。
 */
package com.dualphone.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.dualphone.common.AppBus
import com.dualphone.common.AppPrefs
import com.dualphone.common.Msg
import com.dualphone.service.DualPhoneService

class CallStateMonitor(
    private val context: Context,
    private val onRinging: (number: String) -> Unit,
    private val onIdle: () -> Unit,
    private val onOffhook: () -> Unit,
) {
    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var callback: Any? = null
    private var legacyListener: PhoneStateListener? = null

    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppBus.log("无 READ_PHONE_STATE，无法监听来电", "W")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state, "")
            }
            tm.registerTelephonyCallback(context.mainExecutor, cb)
            callback = cb
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleState(state, phoneNumber ?: "")
                }
            }
            @Suppress("DEPRECATION")
            tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            legacyListener = listener
        }
        AppBus.log("来电监听已启动")
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (callback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
        }
        callback = null
        legacyListener = null
    }

    private fun handleState(state: Int, number: String) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                AppBus.log("来电响铃: $number")
                DualPhoneService.enqueue(
                    context,
                    Msg.CallRinging(number = number.ifBlank { "未知号码" }),
                )
                DualPhoneService.enqueue(context, Msg.CallState(state = "RINGING", number = number))
                onRinging(number)
                val prefs = AppPrefs(context)
                if (prefs.autoAnswer) {
                    CallController.answer(context)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                DualPhoneService.enqueue(context, Msg.CallState(state = "OFFHOOK"))
                onOffhook()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                DualPhoneService.enqueue(context, Msg.CallState(state = "IDLE"))
                DualPhoneService.enqueue(context, Msg.CallHangup())
                onIdle()
            }
        }
    }
}
