/**
 * Host 端短信接收：SMS_RECEIVED 广播 → 解析 PDU → 经 DualPhoneService 推到 Companion。
 * Android 4.4+ 非默认短信应用仍可收到 SMS_RECEIVED，但部分 OEM / Android 14 限制更严。
 */
package com.dualphone.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.dualphone.common.AppBus
import com.dualphone.common.AppPrefs
import com.dualphone.common.DeviceRole
import com.dualphone.common.Msg
import com.dualphone.service.DualPhoneService

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val prefs = AppPrefs(context)
        if (prefs.role != DeviceRole.HOST || !prefs.enableSms) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val from = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: "?"
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val msg = Msg.SmsIn(from = from, body = body)
        AppBus.log("收到短信 $from: ${body.take(40)}")
        DualPhoneService.enqueueReliable(context, msg)
    }
}
