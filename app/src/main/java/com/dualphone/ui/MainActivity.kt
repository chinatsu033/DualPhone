/**
 * Material 3 主界面：①选角色 ②配对码 ③权限 ④启动 + 连接自检。
 * 角色文案：转发端（有 SIM）/ 接收端（显示镜像）。
 */
package com.dualphone.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.dualphone.common.AppBus
import com.dualphone.common.AppPrefs
import com.dualphone.common.ConnMode
import com.dualphone.common.ConnState
import com.dualphone.common.DeviceRole
import com.dualphone.common.NetUtil
import com.dualphone.network.ConnDiagnostics
import com.dualphone.service.DualPhoneService
import com.dualphone.ui.theme.DualPhoneTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DualPhoneTheme {
                DualPhoneScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPhoneScreen() {
    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var step by remember { mutableIntStateOf(0) }
    var role by remember { mutableStateOf(prefs.role) }
    var connMode by remember { mutableStateOf(prefs.connMode) }
    var peerIp by remember { mutableStateOf(prefs.peerIp) }
    var pairingCode by remember {
        mutableStateOf(
            prefs.pairingCode.ifBlank {
                if (prefs.role == DeviceRole.HOST) prefs.generatePairingCode() else ""
            },
        )
    }
    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var enableSms by remember { mutableStateOf(prefs.enableSms) }
    var enableCall by remember { mutableStateOf(prefs.enableCall) }
    var enableNotif by remember { mutableStateOf(prefs.enableNotif) }
    var autoAnswer by remember { mutableStateOf(prefs.autoAnswer) }
    var diagBusy by remember { mutableStateOf(false) }
    val localIp = remember { NetUtil.localIpv4(context) ?: "未知" }

    val conn by AppBus.conn.collectAsState()
    val peer by AppBus.peer.collectAsState()
    val lastError by AppBus.lastError.collectAsState()
    val logs by AppBus.logs.collectAsState()
    val notifs by AppBus.notifs.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) AppBus.log("运行时权限已全部授予")
        else AppBus.log("未授予: $denied", "W")
    }

    fun requestRuntimePermissions() {
        val list = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list += Manifest.permission.ANSWER_PHONE_CALLS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        permissionLauncher.launch(list.toTypedArray())
    }

    fun persist() {
        prefs.role = role
        prefs.connMode = connMode
        prefs.peerIp = peerIp
        prefs.pairingCode = pairingCode
        prefs.deviceName = deviceName
        prefs.enableSms = enableSms
        prefs.enableCall = enableCall
        prefs.enableNotif = enableNotif
        prefs.autoAnswer = autoAnswer
        if (role == DeviceRole.HOST && prefs.pairingCode.length != 6) {
            pairingCode = prefs.generatePairingCode()
        }
    }

    fun startService() {
        persist()
        if (connMode != ConnMode.LAN && pairingCode.length != 6) {
            AppBus.setLastError(
                if (role == DeviceRole.HOST) "请生成 6 位配对码"
                else "请输入转发端显示的 6 位配对码（两边必须相同）",
            )
            return
        }
        DualPhoneService.restart(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DualPhone", fontWeight = FontWeight.SemiBold)
                        Text(
                            "短信 · 来电 · 通知镜像",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    IconButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "应用设置")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                StatusCard(conn = conn, peer = peer, lastError = lastError)
            }

            item {
                StepHeader(1, "选择角色")
                Text(
                    "小米（有业务 SIM）= 转发端；三星 = 接收端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = role == DeviceRole.HOST,
                        onClick = {
                            role = DeviceRole.HOST
                            prefs.role = role
                            if (pairingCode.length != 6) pairingCode = prefs.generatePairingCode()
                            step = 1
                        },
                        label = { Text("转发端") },
                        leadingIcon = {
                            Icon(Icons.Filled.PhoneAndroid, null, Modifier.size(18.dp))
                        },
                    )
                    FilterChip(
                        selected = role == DeviceRole.COMPANION,
                        onClick = {
                            role = DeviceRole.COMPANION
                            prefs.role = role
                            step = 1
                        },
                        label = { Text("接收端") },
                        leadingIcon = {
                            Icon(Icons.Filled.Link, null, Modifier.size(18.dp))
                        },
                    )
                }
                Text(
                    if (role == DeviceRole.HOST) {
                        "转发端 Host：来电 / 短信 / App 通知从这台发出"
                    } else {
                        "接收端 Companion：显示转发过来的短信、来电与通知"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            item {
                StepHeader(2, "配对码（两边必须相同）")
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = {
                        pairingCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6)
                        prefs.pairingCode = pairingCode
                    },
                    label = { Text("6 位配对码") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (role == DeviceRole.HOST) "在转发端生成，再填到接收端"
                            else "请输入转发端屏幕上的同一串码",
                        )
                    },
                    trailingIcon = {
                        Row {
                            if (role == DeviceRole.HOST) {
                                IconButton(onClick = {
                                    pairingCode = prefs.generatePairingCode()
                                }) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = "生成")
                                }
                            }
                            IconButton(onClick = {
                                clipboard.setText(AnnotatedString(pairingCode))
                                AppBus.log("配对码已复制")
                            }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制")
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text("连接方式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = connMode == ConnMode.AUTO,
                        onClick = {
                            connMode = ConnMode.AUTO
                            prefs.connMode = connMode
                        },
                        label = { Text("自动") },
                        leadingIcon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = connMode == ConnMode.LAN,
                        onClick = {
                            connMode = ConnMode.LAN
                            prefs.connMode = connMode
                        },
                        label = { Text("局域网") },
                        leadingIcon = { Icon(Icons.Filled.Router, null, Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = connMode == ConnMode.RELAY,
                        onClick = {
                            connMode = ConnMode.RELAY
                            prefs.connMode = connMode
                        },
                        label = { Text("云中继") },
                        leadingIcon = { Icon(Icons.Filled.Cloud, null, Modifier.size(16.dp)) },
                    )
                }
                Text(
                    when (connMode) {
                        ConnMode.AUTO -> "推荐。同一 Wi‑Fi/热点先走局域网，不通自动改云中继。"
                        ConnMode.LAN -> "只走局域网。本机 IP：$localIp（可给接收端手动填）"
                        ConnMode.RELAY -> "只走流量云中继。两边同一配对码即可。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (connMode != ConnMode.RELAY) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = peerIp,
                        onValueChange = {
                            peerIp = it.trim()
                            prefs.peerIp = peerIp
                        },
                        label = {
                            Text(
                                if (role == DeviceRole.COMPANION) {
                                    "转发端 IP（可空=自动发现）"
                                } else {
                                    "本机局域网 IP（只读参考）"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            if (role == DeviceRole.HOST) Text(localIp) else Text("例如 192.168.1.8")
                        },
                        supportingText = {
                            Text("发现失败时可手动填。TCP 37210 / 发现 UDP 37212")
                        },
                    )
                }
            }

            item {
                StepHeader(3, "权限")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { requestRuntimePermissions() }) {
                        Text("申请权限")
                    }
                    FilledTonalButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("通知使用权") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(
                                Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                                    .putExtra(
                                        TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                                        context.packageName,
                                    ),
                            )
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                        }
                    }) { Text("默认拨号器") }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }) { Text("自启动/电池") }
                }
                val notifOk = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
                AssistChip(
                    onClick = {},
                    label = {
                        Text(if (notifOk) "通知监听：已开" else "通知监听：未开（转发端必开）")
                    },
                )
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = {
                        deviceName = it
                        prefs.deviceName = it
                    },
                    label = { Text("本机显示名") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    singleLine = true,
                )
            }

            if (role == DeviceRole.HOST) {
                item {
                    Text("转发端功能", style = MaterialTheme.typography.titleMedium)
                    ToggleRow("短信转发", enableSms) {
                        enableSms = it
                        prefs.enableSms = it
                    }
                    ToggleRow("来电处理", enableCall) {
                        enableCall = it
                        prefs.enableCall = it
                    }
                    ToggleRow("自动接听（OEM 可能无效）", autoAnswer) {
                        autoAnswer = it
                        prefs.autoAnswer = it
                    }
                    ToggleRow("通知镜像", enableNotif) {
                        enableNotif = it
                        prefs.enableNotif = it
                    }
                }
            }

            item {
                StepHeader(4, "启动")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startService() },
                        modifier = Modifier.weight(1f),
                    ) { Text("启动连接") }
                    OutlinedButton(
                        onClick = { DualPhoneService.stop(context) },
                        modifier = Modifier.weight(1f),
                    ) { Text("停止") }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        if (diagBusy) return@FilledTonalButton
                        persist()
                        diagBusy = true
                        scope.launch {
                            try {
                                ConnDiagnostics.run(context)
                            } finally {
                                diagBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !diagBusy,
                ) {
                    Text(if (diagBusy) "自检进行中…" else "连接自检")
                }
            }

            if (role == DeviceRole.COMPANION && notifs.isNotEmpty()) {
                item {
                    Text("镜像通知 (${notifs.size})", style = MaterialTheme.typography.titleMedium)
                }
                items(notifs.take(20), key = { it.id }) { n ->
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.title ?: n.pkg, style = MaterialTheme.typography.titleSmall)
                            Text(n.text ?: "", style = MaterialTheme.typography.bodyMedium)
                            Text(n.pkg, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Text("日志", style = MaterialTheme.typography.titleMedium)
            }
            items(logs.take(60)) { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun StepHeader(n: Int, title: String) {
    val prefix = listOf("①", "②", "③", "④").getOrElse(n - 1) { "$n." }
    Text(
        "$prefix $title",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun StatusCard(conn: ConnState, peer: String?, lastError: String?) {
    val (title, color) = when (conn) {
        ConnState.CONNECTED -> "已连接" to MaterialTheme.colorScheme.primary
        ConnState.CONNECTING, ConnState.RECONNECTING -> "连接中" to MaterialTheme.colorScheme.tertiary
        ConnState.DISCOVERING -> "发现中" to MaterialTheme.colorScheme.tertiary
        ConnState.DISCONNECTED -> "未连接" to MaterialTheme.colorScheme.outline
    }
    val detail = when {
        conn == ConnState.CONNECTED && peer?.contains("局域网") == true ->
            peer
        conn == ConnState.CONNECTED && peer?.contains("云中继") == true ->
            peer
        conn == ConnState.CONNECTED ->
            "已连接" + (peer?.let { " · $it" } ?: "")
        else -> peer ?: when (conn) {
            ConnState.DISCOVERING -> "正在局域网寻找转发端…"
            ConnState.CONNECTING, ConnState.RECONNECTING -> "请稍候…"
            else -> "点下方「启动连接」"
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (conn) {
                        ConnState.CONNECTED -> Icons.Filled.CheckCircle
                        ConnState.DISCONNECTED -> Icons.Filled.Warning
                        else -> Icons.Filled.Link
                    },
                    contentDescription = null,
                    tint = color,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!lastError.isNullOrBlank()) {
                Text(
                    lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
