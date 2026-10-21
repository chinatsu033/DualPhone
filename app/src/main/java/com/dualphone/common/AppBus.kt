/**
 * 进程内事件总线：日志、连接状态、最近错误、来电、通知列表。
 */
package com.dualphone.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppBus {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _conn = MutableStateFlow(ConnState.DISCONNECTED)
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _peer = MutableStateFlow<String?>(null)
    val peer: StateFlow<String?> = _peer.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _messages = MutableSharedFlow<Msg>(extraBufferCapacity = 64)
    val messages: SharedFlow<Msg> = _messages.asSharedFlow()

    private val _notifs = MutableStateFlow<List<Msg.Notif>>(emptyList())
    val notifs: StateFlow<List<Msg.Notif>> = _notifs.asStateFlow()

    private val _incomingCall = MutableStateFlow<Msg.CallRinging?>(null)
    val incomingCall: StateFlow<Msg.CallRinging?> = _incomingCall.asStateFlow()

    private val logBuffer = CopyOnWriteArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setConn(state: ConnState, peerAddr: String? = _peer.value) {
        _conn.value = state
        if (peerAddr != null) _peer.value = peerAddr
        if (state == ConnState.DISCONNECTED) {
            // 保留 peer 文案到下一次连接说明；错误单独管
        }
        if (state == ConnState.CONNECTED) {
            _lastError.value = null
        }
    }

    fun setLastError(text: String?) {
        _lastError.value = text
        if (!text.isNullOrBlank()) log(text, "E")
    }

    fun clearLastError() {
        _lastError.value = null
    }

    fun log(text: String, level: String = "I") {
        val line = "${timeFmt.format(Date())} [$level] $text"
        logBuffer.add(0, line)
        while (logBuffer.size > 200) logBuffer.removeAt(logBuffer.lastIndex)
        _logs.value = logBuffer.toList()
    }

    fun emit(msg: Msg) {
        _messages.tryEmit(msg)
        when (msg) {
            is Msg.Notif -> {
                val list = _notifs.value.toMutableList()
                list.removeAll { it.id == msg.id }
                list.add(0, msg)
                _notifs.value = list.take(100)
            }
            is Msg.NotifRemoved -> {
                _notifs.value = _notifs.value.filterNot { it.id == msg.id }
            }
            is Msg.CallRinging -> _incomingCall.value = msg
            is Msg.CallState -> {
                if (msg.state == "IDLE" || msg.state == "DISCONNECTED") {
                    _incomingCall.value = null
                }
            }
            is Msg.CallHangup -> _incomingCall.value = null
            else -> Unit
        }
    }

    fun clearIncomingCall() {
        _incomingCall.value = null
    }
}
