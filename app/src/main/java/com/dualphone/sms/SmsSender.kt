/**
 * Host 用本机 SIM 实际发送短信（SmsManager）。
 * Companion 发来的 sms_out 由此处理。
 */
package com.dualphone.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.dualphone.common.AppBus
import com.dualphone.common.Msg

object SmsSender {
    fun send(context: Context, to: String, body: String, id: String): Msg.SmsAck {
        return try {
            @Suppress("DEPRECATION")
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(body)
            val sentIntent = PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                Intent("com.dualphone.SMS_SENT").putExtra("id", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (parts.size == 1) {
                sms.sendTextMessage(to, null, body, sentIntent, null)
            } else {
                val sentIntents = ArrayList<PendingIntent>()
                repeat(parts.size) { sentIntents.add(sentIntent) }
                sms.sendMultipartTextMessage(to, null, parts, sentIntents, null)
            }
            AppBus.log("已用本机 SIM 发送短信 → $to")
            Msg.SmsAck(id = id, ok = true)
        } catch (e: SecurityException) {
            AppBus.log("发送短信失败(权限): ${e.message}", "E")
            Msg.SmsAck(id = id, ok = false, error = e.message)
        } catch (e: Exception) {
            AppBus.log("发送短信失败: ${e.message}", "E")
            Msg.SmsAck(id = id, ok = false, error = e.message)
        }
    }
}
