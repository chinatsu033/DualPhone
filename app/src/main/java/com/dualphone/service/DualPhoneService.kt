/**
 * 前台服务：局域网 TCP/UDP 与云中继 WSS；自动优先局域网。
 * Android 14+ 默认仅以 dataSync 类型拉起，避免无录音权限时 FGS 崩溃。
 */
package com.dualphone.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dualphone.call.BluetoothScoHelper
import com.dualphone.call.CallController
import com.dualphone.call.CallStateMonitor
import com.dualphone.call.IncomingCallActivity
import com.dualphone.common.ActivePath
import com.dualphone.common.AppBus
import com.dualphone.common.AppPrefs
import com.dualphone.common.AutoConnect
import com.dualphone.common.ConnMode
import com.dualphone.common.ConnState
import com.dualphone.common.DeviceRole
import com.dualphone.common.Msg
import com.dualphone.common.NetUtil
import com.dualphone.common.RelayDefaults
import com.dualphone.network.RelayAudioBridge
import com.dualphone.network.TcpControlChannel
import com.dualphone.network.UdpAudioChannel
import com.dualphone.network.UdpDiscovery
import com.dualphone.network.WsRelayChannel
import com.dualphone.notification.CompanionNotifier
import com.dualphone.sms.SmsSender
import com.dualphone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class DualPhoneService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var prefs: AppPrefs
    private var tcp: TcpControlChannel? = null
    private var relay: WsRelayChannel? = null
    private var discovery: UdpDiscovery? = null
    private var audio: UdpAudioChannel? = null
    private var relayAudio: RelayAudioBridge? = null
    private var callMonitor: CallStateMonitor? = null
    private var sco: BluetoothScoHelper? = null
    private var autoJob: Job? = null
    private var monitorJob: Job? = null
    private val activePath = AtomicReference(ActivePath.NONE)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs(this)
        CompanionNotifier.ensureChannels(this)
        startAsForeground()
        AppBus.log("DualPhoneService 已启动 ip=${NetUtil.localIpv4(this)}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafe()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                tearDownNetworking()
                ensureNetworking()
                return START_STICKY
            }
            ACTION_ENQUEUE -> {
                val json = intent.getStringExtra(EXTRA_JSON) ?: return START_STICKY
                val msg = com.dualphone.common.ProtocolCodec.decode(json) ?: return START_STICKY
                val reliable = intent.getBooleanExtra(EXTRA_RELIABLE, false)
                enqueueInternal(msg, reliable)
                return START_STICKY
            }
        }
        ensureNetworking()
        return START_STICKY
    }

    private fun enqueueInternal(msg: Msg, reliable: Boolean) {
        when (prefs.connMode) {
            ConnMode.LAN -> tcp?.enqueue(msg, reliable = reliable)
            ConnMode.RELAY -> relay?.enqueue(msg, reliable = reliable)
            ConnMode.AUTO -> {
                val lanOk = tcp?.isConnected() == true
                val relayOk = relay?.isConnected() == true
                when {
                    lanOk -> tcp?.enqueue(msg, reliable = reliable)
                    relayOk -> relay?.enqueue(msg, reliable = reliable)
                    else -> {
                        tcp?.enqueue(msg, reliable = reliable)
                        relay?.enqueue(msg, reliable = reliable)
                    }
                }
            }
        }
    }

    private fun ensureNetworking() {
        if (tcp != null || relay != null || autoJob != null || monitorJob != null) return
        val role = prefs.role
        when (prefs.connMode) {
            ConnMode.RELAY -> {
                if (!startRelay(role, announce = true)) return
                activePath.set(ActivePath.RELAY)
            }
            ConnMode.LAN -> {
                startLan(role, announce = true)
                activePath.set(ActivePath.LAN)
            }
            ConnMode.AUTO -> startAuto(role)
        }

        if (role == DeviceRole.HOST && prefs.enableCall) {
            sco = BluetoothScoHelper(this)
            callMonitor = CallStateMonitor(
                context = this,
                onRinging = { },
                onIdle = {
                    stopAudio()
                    sco?.stop()
                },
                onOffhook = {
                    sco?.start()
                    startAudio()
                },
            ).also { it.start() }
        }
        refreshForeground()
    }

    private fun startAuto(role: DeviceRole) {
        val code = prefs.ensurePairingCodeForRole()
        if (code == null) {
            AppBus.setLastError("请先填写与转发端相同的 6 位配对码")
            AppBus.setConn(ConnState.DISCONNECTED)
            AppBus.log("自动模式中止：接收端没有配对码", "E")
            return
        }
        AppBus.log("自动模式：局域网优先，失败改云中继（配对码 $code）")
        if (role == DeviceRole.HOST) {
            startLan(role, announce = false)
            startRelay(role, announce = false)
            activePath.set(ActivePath.NONE)
            monitorHostPaths()
            refreshAutoConnState()
        } else {
            autoJob = scope.launch(Dispatchers.IO) {
                AppBus.setConn(ConnState.DISCOVERING)
                AppBus.log("自动模式：探测局域网（最多 ${AutoConnect.LAN_TRY_MS / 1000}s）…")
                startLan(role, announce = false)
                val deadline = System.currentTimeMillis() + AutoConnect.LAN_TRY_MS
                while (isActive && System.currentTimeMillis() < deadline) {
                    if (tcp?.isConnected() == true) {
                        activePath.set(ActivePath.LAN)
                        AppBus.log("自动模式：已走局域网")
                        refreshAutoConnState()
                        monitorCompanionFailoverAndUpgrade()
                        return@launch
                    }
                    delay(200)
                }
                AppBus.log("自动模式：局域网未连通，改用云中继")
                startRelay(role, announce = false)
                activePath.set(ActivePath.RELAY)
                refreshAutoConnState()
                monitorCompanionFailoverAndUpgrade()
            }
        }
    }

    private fun monitorHostPaths() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(800)
                refreshAutoConnState()
            }
        }
    }

    private fun monitorCompanionFailoverAndUpgrade() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1_000)
                val path = activePath.get()
                val lanOk = tcp?.isConnected() == true
                val relayOk = relay?.isConnected() == true

                when (path) {
                    ActivePath.LAN -> {
                        if (!lanOk) {
                            AppBus.log("自动模式：局域网断开，宽限后切中继…", "W")
                            delay(AutoConnect.LAN_FAILOVER_GRACE_MS)
                            if (tcp?.isConnected() == true) continue
                            if (relay == null) startRelay(prefs.role, announce = false)
                            val until = System.currentTimeMillis() + 25_000
                            while (isActive && System.currentTimeMillis() < until &&
                                relay?.isConnected() != true
                            ) {
                                delay(300)
                            }
                            if (relay?.isConnected() == true) {
                                activePath.set(ActivePath.RELAY)
                                AppBus.log("自动模式：已切换到云中继")
                                refreshAutoConnState()
                                if (discovery == null) startDiscoveryOnly(prefs.role)
                            }
                        }
                    }
                    ActivePath.RELAY -> {
                        if (lanOk) {
                            activePath.set(ActivePath.LAN)
                            AppBus.log("自动模式：局域网恢复，切回局域网")
                            refreshAutoConnState()
                        } else {
                            val peer = prefs.peerIp
                            if (tcp == null && peer.isNotBlank()) {
                                startLan(prefs.role, announce = false)
                            } else if (tcp == null) {
                                if (discovery == null) startDiscoveryOnly(prefs.role)
                            }
                            delay(AutoConnect.LAN_UPGRADE_INTERVAL_MS - 1_000)
                        }
                    }
                    ActivePath.NONE -> {
                        when {
                            lanOk -> {
                                activePath.set(ActivePath.LAN)
                                refreshAutoConnState()
                            }
                            relayOk -> {
                                activePath.set(ActivePath.RELAY)
                                refreshAutoConnState()
                            }
                        }
                    }
                }
                refreshAutoConnState()
            }
        }
    }

    private fun refreshAutoConnState() {
        if (prefs.connMode != ConnMode.AUTO) return
        val lanOk = tcp?.isConnected() == true
        val relayOk = relay?.isConnected() == true
        val relayLinked = relay?.isLinked() == true
        when {
            lanOk -> {
                activePath.set(ActivePath.LAN)
                AppBus.setConn(ConnState.CONNECTED, "已连接（局域网） ${tcp?.currentPeer() ?: prefs.peerIp}")
            }
            relayOk -> {
                activePath.set(ActivePath.RELAY)
                AppBus.setConn(ConnState.CONNECTED, "已连接（云中继） ${prefs.pairingCode}")
            }
            relayLinked -> {
                AppBus.setConn(ConnState.CONNECTING, "中继已开·等对端 ${prefs.pairingCode}")
            }
            discovery != null && prefs.role == DeviceRole.COMPANION && tcp != null -> {
                AppBus.setConn(ConnState.DISCOVERING, "正在找转发端…")
            }
            tcp != null || relay != null -> {
                AppBus.setConn(ConnState.CONNECTING)
            }
            else -> AppBus.setConn(ConnState.DISCONNECTED)
        }
        refreshForeground()
    }

    private fun startLan(role: DeviceRole, announce: Boolean) {
        if (tcp != null) return
        val peer = prefs.peerIp
        tcp = TcpControlChannel(
            scope = scope,
            role = role,
            deviceName = prefs.deviceName,
            onMessage = { handleIncoming(it) },
            announceState = announce,
            onLinkChanged = { connected, peerAddr ->
                if (connected && !peerAddr.isNullOrBlank()) {
                    prefs.peerIp = peerAddr
                }
                if (prefs.connMode == ConnMode.AUTO) {
                    scope.launch { refreshAutoConnState() }
                }
            },
        )
        startDiscoveryOnly(role)
        val connectIp = if (role == DeviceRole.COMPANION) peer.ifBlank { null } else null
        tcp?.start(connectIp)
    }

    private fun startDiscoveryOnly(role: DeviceRole) {
        if (discovery != null) return
        val code = prefs.pairingCode
        discovery = UdpDiscovery(
            context = this,
            scope = scope,
            role = role,
            deviceName = prefs.deviceName,
            pairingCode = code,
        ) { ip, _ ->
            if (prefs.peerIp.isBlank() || role == DeviceRole.COMPANION) {
                prefs.peerIp = ip
            }
            tcp?.setPeerIp(ip)
        }.also { it.start() }
    }

    /** @return false 若因配对码缺失未启动 */
    private fun startRelay(role: DeviceRole, announce: Boolean): Boolean {
        if (relay != null) return true
        val url = prefs.relayUrl.trim().ifBlank { RelayDefaults.DEFAULT_URL }
        val code = prefs.ensurePairingCodeForRole()
        if (code == null) {
            AppBus.setLastError("请先填写与转发端相同的 6 位配对码")
            AppBus.log("云中继未启动：接收端缺少配对码", "E")
            if (announce) AppBus.setConn(ConnState.DISCONNECTED)
            return false
        }
        AppBus.log("云中继 url=$url room=$code")
        relay = WsRelayChannel(
            scope = scope,
            role = role,
            deviceName = prefs.deviceName,
            relayBaseUrl = url,
            roomCode = code,
            onMessage = { handleIncoming(it) },
            onAudioFrame = { data -> relayAudio?.onRemotePacket(data) },
            announceState = announce,
            onLinkChanged = { _, _ ->
                if (prefs.connMode == ConnMode.AUTO) {
                    scope.launch { refreshAutoConnState() }
                }
            },
        ).also { it.start() }
        return true
    }

    private fun startAudio() {
        val useLan = when (prefs.connMode) {
            ConnMode.LAN -> true
            ConnMode.RELAY -> false
            ConnMode.AUTO -> activePath.get() == ActivePath.LAN ||
                (tcp?.isConnected() == true && activePath.get() != ActivePath.RELAY)
        }
        if (useLan) {
            val ip = prefs.peerIp.ifBlank { tcp?.currentPeer().orEmpty() }
            if (ip.isBlank()) return
            if (audio == null) audio = UdpAudioChannel(scope)
            audio?.start(ip)
        } else {
            val r = relay ?: return
            if (relayAudio == null) {
                relayAudio = RelayAudioBridge(scope) { pcm -> r.sendAudioPcm(pcm) }
            }
            relayAudio?.start()
        }
    }

    private fun stopAudio() {
        relayAudio?.stop()
        audio?.stop()
    }

    private fun handleIncoming(msg: Msg) {
        when (prefs.role) {
            DeviceRole.HOST -> when (msg) {
                is Msg.SmsOut -> {
                    if (prefs.enableSms) {
                        val ack = SmsSender.send(this, msg.to, msg.body, msg.id)
                        enqueueInternal(ack, reliable = false)
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
                    if (msg.state == "OFFHOOK") startAudio()
                    if (msg.state == "IDLE") {
                        stopAudio()
                        AppBus.clearIncomingCall()
                    }
                }
                is Msg.CallHangup -> {
                    stopAudio()
                    AppBus.clearIncomingCall()
                }
                is Msg.Hello -> AppBus.log("对端 Hello: ${msg.name} (${msg.role})")
                else -> Unit
            }
        }
    }

    private fun modeLabel(): String = when (prefs.connMode) {
        ConnMode.AUTO -> when (activePath.get()) {
            ActivePath.LAN -> "自动·局域网"
            ActivePath.RELAY -> "自动·云中继 ${prefs.pairingCode}"
            ActivePath.NONE -> "自动·连接中 ${prefs.pairingCode}"
        }
        ConnMode.LAN -> "仅局域网"
        ConnMode.RELAY -> "仅云中继 ${prefs.pairingCode}"
    }

    private fun roleLabel(): String =
        if (prefs.role == DeviceRole.HOST) "转发端" else "接收端"

    private fun foregroundTypes(): Int {
        var t = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 默认只用 dataSync，避免无 RECORD_AUDIO 时 startForeground 直接崩
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (mic) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        return t
    }

    private fun startAsForeground() {
        CompanionNotifier.ensureChannels(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CompanionNotifier.CHANNEL_SERVICE)
            .setContentTitle("DualPhone 运行中")
            .setContentText("${roleLabel()} · ${modeLabel()}")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            val types = foregroundTypes()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                startForeground(1001, n, types)
            } else {
                startForeground(1001, n)
            }
        } catch (e: Exception) {
            AppBus.log("前台服务启动降级: ${e.message}", "W")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(1001, n)
                }
            } catch (e2: Exception) {
                AppBus.setLastError("无法启动前台服务: ${e2.message}")
            }
        }
    }

    private fun refreshForeground() {
        try {
            startAsForeground()
        } catch (_: Exception) {
        }
    }

    private fun tearDownNetworking() {
        autoJob?.cancel()
        autoJob = null
        monitorJob?.cancel()
        monitorJob = null
        callMonitor?.stop()
        callMonitor = null
        discovery?.stop()
        discovery = null
        stopAudio()
        sco?.stop()
        sco = null
        audio = null
        relayAudio = null
        tcp?.stop()
        tcp = null
        relay?.stop()
        relay = null
        activePath.set(ActivePath.NONE)
    }

    private fun stopSelfSafe() {
        tearDownNetworking()
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
        const val ACTION_RESTART = "com.dualphone.RESTART"
        const val ACTION_ENQUEUE = "com.dualphone.ENQUEUE"
        const val EXTRA_JSON = "json"
        const val EXTRA_RELIABLE = "reliable"

        @Volatile
        private var instance: DualPhoneService? = null

        fun start(context: Context) {
            ContextCompatStart(context, Intent(context, DualPhoneService::class.java))
        }

        fun stop(context: Context) {
            ContextCompatStart(
                context,
                Intent(context, DualPhoneService::class.java).setAction(ACTION_STOP),
            )
        }

        fun restart(context: Context) {
            ContextCompatStart(
                context,
                Intent(context, DualPhoneService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun enqueue(context: Context, msg: Msg, reliable: Boolean = false) {
            instance?.enqueueInternal(msg, reliable) ?: run {
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
