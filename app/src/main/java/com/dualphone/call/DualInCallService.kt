/**
 * InCallService：若用户将 DualPhone 设为默认拨号器 / 启用通话界面，可拿到 Connection 并接听挂断。
 * 多数用户不会设为默认拨号器，此时此类可能不被系统绑定。
 */
package com.dualphone.call

import android.telecom.Call
import android.telecom.InCallService
import com.dualphone.common.AppBus

class DualInCallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeCall = call
        AppBus.log("InCallService onCallAdded")
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                AppBus.log("通话状态=$state")
                if (state == Call.STATE_DISCONNECTED) {
                    if (activeCall == call) activeCall = null
                }
            }
        })
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (activeCall == call) activeCall = null
    }

    companion object {
        @Volatile
        private var activeCall: Call? = null

        fun answerActive() {
            try {
                activeCall?.answer(0)
                AppBus.log("InCallService.answer")
            } catch (e: Exception) {
                AppBus.log("InCall answer 失败: ${e.message}", "W")
            }
        }

        fun hangupActive() {
            try {
                activeCall?.disconnect()
                AppBus.log("InCallService.disconnect")
            } catch (e: Exception) {
                AppBus.log("InCall hangup 失败: ${e.message}", "W")
            }
        }
    }
}
