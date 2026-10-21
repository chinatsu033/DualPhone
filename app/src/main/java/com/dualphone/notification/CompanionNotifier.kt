/**
 * Companion：根据收到的 Notif 重建系统通知；支持分组键与优先级字段（尽力保留）。
 */
package com.dualphone.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dualphone.common.Msg
import com.dualphone.sms.SmsReplyActivity
import com.dualphone.ui.MainActivity

object CompanionNotifier {
    const val CHANNEL_MIRROR = "mirror_notifs"
    const val CHANNEL_SMS = "sms_forward"
    const val CHANNEL_CALL = "incoming_call"
    const val CHANNEL_SERVICE = "dualphone_service"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MIRROR, "镜像通知", NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SMS, "短信转发", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "来电", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "DualPhone 服务", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun showMirrored(context: Context, notif: Msg.Notif) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            notif.id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val importancePriority = when {
            notif.priority >= NotificationCompat.PRIORITY_HIGH -> NotificationCompat.PRIORITY_HIGH
            notif.priority <= NotificationCompat.PRIORITY_LOW -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_MIRROR)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notif.title ?: notif.pkg)
            .setContentText(notif.text ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(notif.text ?: ""))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(importancePriority)
            .setGroup(notif.groupKey)
            .setSubText(notif.pkg)
        try {
            NotificationManagerCompat.from(context)
                .notify(notif.id.hashCode(), builder.build())
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS
        }
    }

    fun cancelMirrored(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }

    fun showSms(context: Context, sms: Msg.SmsIn) {
        ensureChannels(context)
        val reply = Intent(context, SmsReplyActivity::class.java).apply {
            putExtra(SmsReplyActivity.EXTRA_TO, sms.from)
            putExtra(SmsReplyActivity.EXTRA_BODY, sms.body)
        }
        val pi = PendingIntent.getActivity(
            context,
            sms.id.hashCode(),
            reply,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_SMS)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("短信 · ${sms.from}")
            .setContentText(sms.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(sms.body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(sms.id.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
