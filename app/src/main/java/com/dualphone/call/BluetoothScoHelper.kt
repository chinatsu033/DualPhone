/**
 * 尝试开启蓝牙 SCO，把通话音频走耳机通路。
 * 仅在有已连接耳机时有效；失败则由 UDP PCM 尽力传输（通话上行常被 OEM 静音）。
 */
package com.dualphone.call

import android.content.Context
import android.media.AudioManager
import com.dualphone.common.AppBus

class BluetoothScoHelper(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun start() {
        try {
            if (!am.isBluetoothScoAvailableOffCall && !am.isBluetoothScoOn) {
                // 仍尝试
            }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            AppBus.log("已请求 Bluetooth SCO")
        } catch (e: Exception) {
            AppBus.log("SCO 失败: ${e.message}", "W")
        }
    }

    fun stop() {
        try {
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }
}
