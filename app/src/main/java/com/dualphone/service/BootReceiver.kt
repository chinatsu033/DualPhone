/**
 * 开机后尝试拉起前台服务（仍受 OEM 自启动限制）。
 */
package com.dualphone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dualphone.common.AppBus

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        AppBus.log("开机广播，尝试启动服务")
        DualPhoneService.start(context)
    }
}
