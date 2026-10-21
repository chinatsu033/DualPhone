/**
 * Application 入口：初始化通知渠道等。
 */
package com.dualphone

import android.app.Application
import com.dualphone.notification.CompanionNotifier

class DualPhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CompanionNotifier.ensureChannels(this)
    }
}
