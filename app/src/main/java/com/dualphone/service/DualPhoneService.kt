/**
 * 前台服务：持有 TCP/UDP、来电监听、消息分发。
 * Host 与 Companion 共用同一服务，按角色分支行为。
 */
package com.dualphone.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dualphone.call.BluetoothScoHelper
import com.dualphone.call.CallController
import com.dualphone.call.CallStateMonitor
import com.dualphone.call.IncomingCallActivity
import com.dualphone.common.AppBus
import com.dualphone.common.AppPrefs
import com.dualphone.common.DeviceRole
import com.dualphone.common.Msg
import com.dualphone.network.TcpControlChannel
import com.dualphone.network.UdpAudioChannel
import com.dualphone.network.UdpDiscovery
import com.dualphone.notification.CompanionNotifier
import com.dualphone.sms.SmsSender
import com.dualphone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class DualPhoneService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var prefs: AppPrefs
    private var tcp: TcpControlChannel? = null
    private var discovery: UdpDiscovery? = null
    private var audio: UdpAudioChannel? = null
    private var callMonitor: CallStateMonitor? = null
    private var sco: BluetoothScoHelper? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs(this)
        CompanionNotifier.ensureChannels(this)
        startAsForeground()
        AppBus.log("DualPhoneService 已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafe()
                return START_NOT_STICKY
            }
            ACTION_ENQUEUE -> {
                val json = intent.getStringExtra(EXTRA_JSON) ?: return START_STICKY
                val msg = com.dualphone.common.ProtocolCodec.decode(json) ?: return START_STICKY
                val reliable = intent.getBooleanExtra(EXTRA_RELIABLE, false)
                tcp?.enqueue(msg, reliable = reliable)
                return START_STICKY
            }
        }
        ensureNetworking()
        return START_STICKY
    }

    private fun ensureNetworking() {
        if (tcp != null) return
        val role = prefs.role
        val peer = prefs.peerIp
        tcp = TcpControlChannel(scope, role, prefs.deviceName) { msg ->
            handleIncoming(msg)
        }
        discovery = UdpDiscovery(scope, role, prefs.deviceName) { ip, _ ->
            if (prefs.peerIp.isBlank() || role == DeviceRole.COMPANION) {
                prefs.peerIp = ip
                // Companion 发现 Host 后若尚未连上，重启 TCP 客户端
                if (role == DeviceRole.COMPANION && tcp != null) {
                    // peer 已更新；若仍在重连循环会用新 IP
                }
            }
        }.also { it.start() }

        val connectIp = if (role == DeviceRole.COMPANION) peer.ifBlank { null } else null
        tcp?.start(connectIp)

        if (role == DeviceRole.HOST && prefs.enableCall) {
            sco = BluetoothScoHelper(this)
            callMonitor = CallStateMonitor(
                context = this,
                onRinging = { /* 已在 monitor 内发消息 */ },
                onIdle = {
                    audio?.stop()
                    sco?.stop()
                },
                onOffhook = {
                    sco?.start()
                    val ip = prefs.peerIp
                    if (ip.isNotBlank()) {
                        if (audio == null) audio = UdpAudioChannel(scope)
                        audio?.start(ip)
                    }
                },
            ).also { it.start() }
        }
    }

    private fun handleIncoming(msg: Msg) {
        when (prefs.role) {
            DeviceRole.HOST -> when (msg) {
                is Msg.SmsOut -> {
                    if (prefs.enableSms) {
                        val ack = SmsSender.send(this, msg.to, msg.body, msg.id)
                        tcp?.enqueue(ack)
                    }
                }
                is Msg.CallAnswer -> CallController.answer(this)
                is Msg.CallHangup -> CallController.hangup(this)
                is Msg.Hello -> AppBus.log("对端 Hello: ${msg.name} (${msg.role})")
                else -> Unit
            }
            DeviceRole.COMPANION -> when (msg) {
                is Msg.SmsIn -> CompanionNotifier.showSms(this, msg)
                is Msg.Notif -> CompanionNotifier.showMirrored(this, msg)
                is Msg.NotifRemoved -> CompanionNotifier.cancelMirrored(this, msg.id)
                is Msg.CallRinging -> {
                    val i = Intent(this, IncomingCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(IncomingCallActivity.EXTRA_NUMBER, msg.number)
                        putExtra(IncomingCallActivity.EXTRA_NAME, msg.name)
                    }
                    startActivity(i)
                }
                is Msg.CallState -> {
                    if (msg.state == "OFFHOOK") {
                        val ip = prefs.peerIp
                        if (ip.isNotBlank()) {
                            if (audio == null) audio = UdpAudioChannel(scope)
                            audio?.start(ip)
                        }
                    }
                    if (msg.state == "IDLE") {
                        audio?.stop()
                        AppBus.clearIncomingCall()
                    }
                }
                is Msg.CallHangup -> {
                    audio?.stop()
                    AppBus.clearIncomingCall()
                }
                is Msg.Hello -> {
                    AppBus.log("对端 Hello: ${msg.name} (${msg.role})")
                    // 记下对端 IP（已在 TCP 层）
                }
                else -> Unit
            }
        }
    }

    private fun startAsForeground() {
        CompanionNotifier.ensureChannels(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CompanionNotifier.CHANNEL_SERVICE)
            .setContentTitle("DualPhone 运行中")
            .setContentText(if (prefs.role == DeviceRole.HOST) "主机模式" else "伴侣模式")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1001, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(1001, n)
        }
    }

    private fun stopSelfSafe() {
        callMonitor?.stop()
        discovery?.stop()
        audio?.stop()
        sco?.stop()
        tcp?.stop()
        tcp = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfSafe()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dualphone.STOP"
        const val ACTION_ENQUEUE = "com.dualphone.ENQUEUE"
        const val EXTRA_JSON = "json"
        const val EXTRA_RELIABLE = "reliable"

        @Volatile
        private var instance: DualPhoneService? = null

        fun start(context: Context) {
            val i = Intent(context, DualPhoneService::class.java)
            ContextCompatStart(context, i)
        }

        fun stop(context: Context) {
            val i = Intent(context, DualPhoneService::class.java).setAction(ACTION_STOP)
            ContextCompatStart(context, i)
        }

        fun enqueue(context: Context, msg: Msg, reliable: Boolean = false) {
            instance?.tcp?.enqueue(msg, reliable = reliable) ?: run {
                // 服务未起时用 Intent 暂存一次启动
                val i = Intent(context, DualPhoneService::class.java).apply {
                    action = ACTION_ENQUEUE
                    putExtra(EXTRA_JSON, com.dualphone.common.ProtocolCodec.encode(msg))
                    putExtra(EXTRA_RELIABLE, reliable)
                }
                ContextCompatStart(context, i)
            }
        }

        fun enqueueReliable(context: Context, msg: Msg) = enqueue(context, msg, reliable = true)

        private fun ContextCompatStart(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
