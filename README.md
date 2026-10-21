# DualPhone

把一台安卓手机 **A（Host / 主机）** 的短信、来电与通知，经同一 Wi‑Fi 实时镜像到另一台 **B（Companion / 伴侣）**。同一 APK，安装后在界面选择角色。

> **English (short):** DualPhone mirrors SMS, calls, and notifications from phone A to phone B over LAN (TCP control + UDP audio). Same APK, pick Host/Companion in UI. Best-effort; OEM limits apply (especially call audio & auto-answer on Xiaomi). LAN only — do not use on untrusted networks. MIT licensed.

## 功能

| 功能 | Host (A) | Companion (B) |
|------|----------|---------------|
| 短信 | `SMS_RECEIVED` → TCP JSON | 系统通知；点击回复 → Host 用 SIM 发送 |
| 来电 | 检测响铃；可选自动接听；SCO / UDP 音频 | 全屏来电页，接听/挂断指令回传 |
| 通知 | `NotificationListenerService` | 重建通知（保留 group/priority 字段） |
| 传输 | TCP 控制（JSON 行）+ UDP 音频 + UDP 发现 | 自动重连；可靠消息队列重放 |

## 协议摘要

TCP 每行一条 JSON，例如：

```json
{"type":"sms_in","id":"...","from":"+86...","body":"验证码","ts":1710000000000}
{"type":"sms_out","id":"...","to":"+86...","body":"收到"}
{"type":"call_ringing","number":"...","name":null}
{"type":"call_answer"}
{"type":"call_hangup"}
{"type":"call_state","state":"RINGING|OFFHOOK|IDLE"}
{"type":"notif","id":"pkg:id:tag","pkg":"...","title":"...","text":"...","groupKey":"...","priority":0}
{"type":"hello","role":"host|companion","name":"Pixel","port":37210}
```

UDP 音频帧：`magic(0x44504155) + seq + len + PCM16LE mono 16kHz`（约 20ms/帧）。默认端口：TCP `37210`，音频 `37211`，发现 `37212`。

## 工程结构

```
com.dualphone
├── common/          协议模型、Prefs、事件总线
├── network/         TCP 控制、UDP 发现、UDP 音频
├── sms/             收信广播、发信、回复页
├── call/            来电监听、接听挂断、InCallService、来电 UI、SCO
├── notification/    通知监听、Companion 重建通知
├── service/         前台服务编排
└── ui/              Material3 主界面
```

## 环境要求

- Android Studio Hedgehog+ / AGP 8.7
- JDK 17
- `minSdk 29`（Android 10），`targetSdk 34`，`compileSdk 34`
- 两台手机同一 Wi‑Fi（勿用访客网络隔离）

## 安装与配对

1. Clone 后用 Android Studio 打开，或：
   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. **两台都安装同一 APK**。
3. 手机 A：角色选 **主机 A**，点「申请权限」→「通知使用权」→（可选）「默认拨号器」→「启动服务」。
4. 手机 B：角色选 **伴侣 B**，可留空 IP 等自动发现，或手动填 A 的局域网 IP →「启动服务」。
5. 状态变为 **已连接** 后测短信/通知；来电需额外授权且受 OEM 限制。

## 权限说明

| 权限 | 用途 |
|------|------|
| `RECEIVE_SMS` / `READ_SMS` / `SEND_SMS` | A 收信转发；代发 B 的回复 |
| `READ_PHONE_STATE` | 检测来电状态 |
| `ANSWER_PHONE_CALLS` / `MANAGE_OWN_CALLS` | 接听/挂断（常需默认拨号器） |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 读取 A 的通知（系统设置里手动开） |
| `BIND_INCALL_SERVICE` | 系统通话界面回调（需成为拨号器） |
| `RECORD_AUDIO` | UDP 双向音频 |
| `BLUETOOTH_CONNECT` | 尝试 Bluetooth SCO |
| `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES` | 部分机型 Wi‑Fi / 发现需要 |
| `FOREGROUND_SERVICE_*` | 常驻前台服务 |
| `POST_NOTIFICATIONS` | B 弹出镜像/短信通知（Android 13+） |

小米等机型请在系统设置中允许：**自启动、后台运行、关闭电池优化、通知使用权**。

## 已知限制（请务必阅读）

1. **通话音频**：系统通话上行麦克风常被 OEM 独占，UDP 流可能只有环境音或静音；Bluetooth SCO 仅在已连接耳机且系统允许时有效。**<500ms 延迟为尽力目标，非保证**。
2. **自动接听**：`TelecomManager.acceptRingingCall()` / `InCallService` 在未设为默认拨号器时经常无效，小米限制尤其严。
3. **短信**：Android 4.4+ 非默认短信应用仍可能收到 `SMS_RECEIVED`，但 Android 14+ / 部分 ROM 可能更严；无法替代官方短信应用做完整会话存档。
4. **通知**：需用户手动开通知使用权；加密通知、部分系统通知可能读不到正文。
5. **安全**：流量为**明文局域网**，仅用于你自己的设备；请勿在公共 Wi‑Fi 使用。本项目**不是**商业远程控制方案。
6. **稳定性**：断线会自动重连并尽力重放短信/通知队列；音频丢包不重传。
7. **构建**：Release 签名需自备；仓库默认提供 Debug 构建方式。

## License

MIT — see [LICENSE](LICENSE).
