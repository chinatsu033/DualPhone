/**
 * Host：NotificationListenerService 读取本机通知，转 JSON 经服务推到 Companion。
 * 用户必须在系统设置里手动授权「通知使用权」。
 */
package com.dualphone.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.dualphone.common.AppBus
import com.dualphone.common.AppPrefs
import com.dualphone.common.DeviceRole
import com.dualphone.common.Msg
import com.dualphone.service.DualPhoneService

class MirrorNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        AppBus.log("通知监听已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = AppPrefs(this)
        if (prefs.role != DeviceRole.HOST || !prefs.enableNotif) return
        if (sbn.packageName == packageName) return // 忽略自己

        val n = sbn.notification
        val extras = n.extras
        val title = extras?.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
        val groupKey = sbn.groupKey
        val priority = n.priority
        val id = "${sbn.packageName}:${sbn.id}:${sbn.tag}"
        val msg = Msg.Notif(
            id = id,
            pkg = sbn.packageName,
            title = title,
            text = text,
            groupKey = groupKey,
            priority = priority,
        )
        DualPhoneService.enqueueReliable(this, msg)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val prefs = AppPrefs(this)
        if (prefs.role != DeviceRole.HOST || !prefs.enableNotif) return
        val id = "${sbn.packageName}:${sbn.id}:${sbn.tag}"
        DualPhoneService.enqueue(this, Msg.NotifRemoved(id = id))
    }
}
