/**
 * 主界面：Host/Companion、局域网 / 云中继、功能开关、连接状态与日志。
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.dualphone.common.AppBus
import com.dualphone.common.AppPrefs
import com.dualphone.common.ConnMode
import com.dualphone.common.ConnState
import com.dualphone.common.DeviceRole
import com.dualphone.service.DualPhoneService
import com.dualphone.ui.theme.DualPhoneTheme

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
    var role by remember { mutableStateOf(prefs.role) }
    var connMode by remember { mutableStateOf(prefs.connMode) }
    var peerIp by remember { mutableStateOf(prefs.peerIp) }
    var pairingCode by remember { mutableStateOf(prefs.pairingCode) }
    var relayUrl by remember { mutableStateOf(prefs.relayUrl) }
    var deviceName by remember { mutableStateOf(prefs.deviceName) }
    var enableSms by remember { mutableStateOf(prefs.enableSms) }
    var enableCall by remember { mutableStateOf(prefs.enableCall) }
    var enableNotif by remember { mutableStateOf(prefs.enableNotif) }
    var autoAnswer by remember { mutableStateOf(prefs.autoAnswer) }

    val conn by AppBus.conn.collectAsState()
    val peer by AppBus.peer.collectAsState()
    val logs by AppBus.logs.collectAsState()
    val notifs by AppBus.notifs.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        AppBus.log("权限结果: ${result.filterValues { !it }.keys}")
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("DualPhone") })
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("角色", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = role == DeviceRole.HOST,
                        onClick = {
                            role = DeviceRole.HOST
                            prefs.role = role
                        },
                        label = { Text("主机 A（有 SIM）") },
                    )
                    FilterChip(
                        selected = role == DeviceRole.COMPANION,
                        onClick = {
                            role = DeviceRole.COMPANION
                            prefs.role = role
                        },
                        label = { Text("伴侣 B（镜像）") },
                    )
                }
            }
            item {
                Text("连接方式", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = connMode == ConnMode.LAN,
                        onClick = {
                            connMode = ConnMode.LAN
                            prefs.connMode = connMode
                        },
                        label = { Text("同一 Wi‑Fi") },
                    )
                    FilterChip(
                        selected = connMode == ConnMode.RELAY,
                        onClick = {
                            connMode = ConnMode.RELAY
                            prefs.connMode = connMode
                        },
                        label = { Text("云中继（流量可用）") },
                    )
                }
                Text(
                    when (connMode) {
                        ConnMode.LAN -> "两台同一局域网；也可开热点（一方开热点另一方连上，仍走本机流量）。"
                        ConnMode.RELAY -> "双方输入相同配对码，经云端 WSS 转发；延迟更高，通话可能 >500ms。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = {
                        deviceName = it
                        prefs.deviceName = it
                    },
                    label = { Text("本机名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (connMode == ConnMode.LAN) {
                item {
                    OutlinedTextField(
                        value = peerIp,
                        onValueChange = {
                            peerIp = it
                            prefs.peerIp = it
                        },
                        label = { Text("对端 IP（可空=自动发现）") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text("TCP ${com.dualphone.common.Ports.TCP_CONTROL} / UDP 音频 ${com.dualphone.common.Ports.UDP_AUDIO}")
                        },
                    )
                }
            } else {
                item {
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = {
                            pairingCode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6)
                            prefs.pairingCode = pairingCode
                        },
                        label = { Text("配对码（6 位）") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("两台手机填同一个码；主机可点「生成」") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val code = prefs.generatePairingCode()
                            pairingCode = code
                        }) { Text("生成配对码") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = {
                            relayUrl = it
                            prefs.relayUrl = it
                        },
                        label = { Text("中继地址") },
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("部署 relay/ 后填 https://….workers.dev；勿带末尾斜杠") },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "连接: ${connLabel(conn)}" + (peer?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (connMode == ConnMode.RELAY && pairingCode.isNotBlank()) {
                            Text("配对码 $pairingCode", fontSize = 13.sp)
                        }
                    }
                }
            }
            if (role == DeviceRole.HOST) {
                item {
                    Text("主机功能", style = MaterialTheme.typography.titleMedium)
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
                Text("权限与系统设置", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { requestRuntimePermissions() }) { Text("申请权限") }
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        )
                    }) { Text("通知使用权") }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        try {
                            val i = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                                .putExtra(
                                    TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                                    context.packageName,
                                )
                            context.startActivity(i)
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                        }
                    }) { Text("默认拨号器") }
                    Button(onClick = {
                        val i = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                        context.startActivity(i)
                    }) { Text("自启动/电池") }
                }
                Text(
                    "通知监听授权: " +
                        if (NotificationManagerCompat.getEnabledListenerPackages(context)
                                .contains(context.packageName)
                        ) "已开" else "未开",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        prefs.role = role
                        prefs.connMode = connMode
                        prefs.peerIp = peerIp
                        prefs.pairingCode = pairingCode
                        prefs.relayUrl = relayUrl
                        if (connMode == ConnMode.RELAY && prefs.pairingCode.length != 6) {
                            pairingCode = prefs.generatePairingCode()
                        }
                        DualPhoneService.restart(context)
                    }) { Text("启动服务") }
                    Button(onClick = { DualPhoneService.stop(context) }) { Text("停止") }
                }
            }
            if (role == DeviceRole.COMPANION) {
                item {
                    Text("镜像通知 (${notifs.size})", style = MaterialTheme.typography.titleMedium)
                }
                items(notifs.take(30), key = { it.id }) { n ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(n.title ?: n.pkg, style = MaterialTheme.typography.titleSmall)
                            Text(n.text ?: "", fontSize = 13.sp)
                            Text(n.pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            item {
                Text("日志", style = MaterialTheme.typography.titleMedium)
            }
            items(logs.take(80)) { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            item { Spacer(Modifier.height(24.dp)) }
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

private fun connLabel(s: ConnState): String = when (s) {
    ConnState.DISCONNECTED -> "未连接"
    ConnState.DISCOVERING -> "发现中"
    ConnState.CONNECTING -> "连接中"
    ConnState.CONNECTED -> "已连接"
    ConnState.RECONNECTING -> "重连中"
}
