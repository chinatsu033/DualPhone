# DualPhone

把一台安卓手机 **A（Host / 主机）** 的短信、来电与通知，实时镜像到另一台 **B（Companion / 伴侣）**。同一 APK，安装后在界面选择角色与连接方式。

> **English (short):** DualPhone mirrors SMS, calls, and notifications from phone A to phone B. Modes: same Wi‑Fi (TCP+UDP) or **cloud relay** (WSS, works on cellular). Same APK, pick Host/Companion. Best-effort; OEM limits apply. MIT licensed.

## 连接方式

| 模式 | 何时用 | 说明 |
|------|--------|------|
| **同一 Wi‑Fi** | 两台能进同一局域网 | TCP 控制 + UDP 音频 + UDP 发现；延迟最低 |
| **云中继（流量可用）** | 两边都用手机流量 / 不同网络 | 双方输入相同 **6 位配对码**，经 Cloudflare Worker WSS 转发；音频改走同一 WebSocket 二进制帧 |
| **热点（零服务器）** | 不想用中继、又能有一方出流量 | 一方开个人热点，另一方连上 → 仍选「同一 Wi‑Fi」，走局域网栈 |

蜂窝网直连手机↔手机 TCP/UDP 通常因 CGNAT 失败，因此流量场景请用 **云中继** 或 **热点**。

### 云中继配对（流量）

1. 两台都装同一版本 APK，选好 Host / Companion。
2. 连接方式选 **云中继（流量可用）**。
3. Host 点「生成配对码」，把 6 位码填到 Companion（或口播）。
4. 两边都点「启动服务」；状态变为已连接且日志出现 `relay_status … ready:true` 即可。
5. 在「中继地址」填入你部署的 Worker（见 [`relay/`](relay/)）。仓库未附带长期公共中继：本机 CI token 无 Workers 写权限，需你在 Cloudflare 上 `cd relay && npx wrangler deploy` 一次。

**延迟预期：** 控制消息通常可接受；通话音频经中继往返，**经常超过 500ms**，视运营商与 Worker 区域而定。中继会看到明文控制 JSON 与音频帧，请只用于自己的设备。

## 功能

| 功能 | Host (A) | Companion (B) |
|------|----------|---------------|
| 短信 | `SMS_RECEIVED` → 控制通道 | 系统通知；点击回复 → Host 用 SIM 发送 |
| 来电 | 检测响铃；可选自动接听；SCO / 音频 | 全屏来电页，接听/挂断指令回传 |
| 通知 | `NotificationListenerService` | 重建通知（保留 group/priority 字段） |
| 传输 | LAN：TCP+UDP；中继：WSS 文本+二进制 | 自动重连；可靠消息队列重放 |

## 协议摘要

控制通道每条消息为 JSON（LAN 为 TCP 一行一条；中继为 WebSocket 文本帧），例如：

```json
{"type":"sms_in","id":"...","from":"+86...","body":"验证码","ts":1710000000000}
{"type":"hello","role":"host|companion","name":"Pixel","port":37210}
```

音频：`magic(0x44504155) + seq + len + PCM16LE mono 16kHz`（约 20ms/帧）。LAN 走 UDP `37211`；中继走 WebSocket 二进制帧。

中继 URL：`wss://<host>/ws?room=ABC123&role=host|companion`。

## 工程结构

```
com.dualphone
├── common/          协议模型、Prefs、事件总线
├── network/         TCP、UDP、WSS 中继、音频桥
├── sms/ call/ notification/ service/ ui/
relay/               Cloudflare Worker + 可选 Node 中继
```

## 环境要求

- Android Studio Hedgehog+ / AGP 8.7，JDK 17
- `minSdk 29`，`targetSdk 34`
- LAN 模式：同一 Wi‑Fi（勿用访客隔离）或热点
- 中继模式：两台都能访问中继 HTTPS/WSS

## 安装与配对

1. 从 [Releases](https://github.com/chinatsu033/DualPhone/releases) 下载 APK，或：
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. **两台都安装同一 APK**（debug 包名 `com.dualphone.debug`）。
3. 按上文选择 Wi‑Fi 或云中继并启动服务。
4. Host：申请权限 → 通知使用权 →（可选）默认拨号器。

### 部署自己的中继

```bash
cd relay
npx wrangler deploy
```

把 App 里「中继地址」改成你的 `https://dualphone-relay.<subdomain>.workers.dev`。详见 [`relay/README.md`](relay/README.md)。

## 权限说明

| 权限 | 用途 |
|------|------|
| `RECEIVE_SMS` / `READ_SMS` / `SEND_SMS` | A 收信转发；代发 B 的回复 |
| `READ_PHONE_STATE` / `ANSWER_PHONE_CALLS` / `MANAGE_OWN_CALLS` | 来电 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 通知镜像（系统设置手动开） |
| `RECORD_AUDIO` | 双向音频 |
| `INTERNET` | 中继 / 网络 |
| `BLUETOOTH_CONNECT` / 定位 / `NEARBY_WIFI_DEVICES` | SCO / 发现（LAN） |

小米等机型请允许：自启动、后台运行、关闭电池优化、通知使用权。

## 已知限制

1. **通话音频**：OEM 可能禁止通话上行采集；中继模式下延迟常 **>500ms**。
2. **自动接听**：常需默认拨号器；小米限制严。
3. **中继信任**：公共 Worker 可转发你的短信/通知/音频元数据；敏感场景请自建并换地址。
4. **安全**：LAN 为明文局域网；中继为 TLS 到 Worker，Worker 侧可见明文载荷。
5. **构建**：仓库默认 Debug 签名，适合侧载；Release 需自备 keystore。

## License

MIT — see [LICENSE](LICENSE).
