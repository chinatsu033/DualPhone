/**
 * 接听 / 挂断：优先 TelecomManager，其次 InCallService Connection。
 * 许多 OEM（尤其小米）会限制非默认拨号器自动接听。
 */
package com.dualphone.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.dualphone.common.AppBus

object CallController {
    fun answer(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    tm.acceptRingingCall()
                    AppBus.log("已请求接听 (TelecomManager.acceptRingingCall)")
                    return
                }
            }
            // 回退：耳机键模拟（兼容性差，尽力而为）
            @Suppress("DEPRECATION")
            val buttonUp = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(
                    Intent.EXTRA_KEY_EVENT,
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK),
                )
            }
            context.sendOrderedBroadcast(buttonUp, null)
            AppBus.log("已尝试耳机键接听（兼容回退）", "W")
        } catch (e: Exception) {
            AppBus.log("接听失败: ${e.message}", "E")
        }
        DualInCallService.answerActive()
    }

    fun hangup(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                    tm.endCall()
                    AppBus.log("已请求挂断 (TelecomManager.endCall)")
                    return
                }
            }
        } catch (e: Exception) {
            AppBus.log("挂断失败: ${e.message}", "E")
        }
        DualInCallService.hangupActive()
    }
}
