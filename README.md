# DualPhone

把一台安卓手机 **A（Host / 主机）** 的短信、来电与通知，实时镜像到另一台 **B（Companion / 伴侣）**。同一 APK，安装后在界面选择角色与连接方式。

> **English (short):** DualPhone mirrors SMS, calls, and notifications from phone A to phone B. Default **Auto** prefers LAN, falls back to cloud relay on cellular. Same APK, pick Host/Companion. Best-effort; OEM limits apply. MIT licensed.

## 连接方式

| 模式 | 何时用 | 说明 |
|------|--------|------|
| **自动（默认）** | 日常推荐 | 先试局域网（发现 / 已知 IP）；不通则自动走云中继。局域网掉线会切中继，恢复后可再升回 |
| **仅局域网** | 两台同一 Wi‑Fi / 热点 | TCP 控制 + UDP 音频 + UDP 发现；延迟最低 |
| **仅云中继** | 强制走流量 | 双方同一 **6 位配对码**，经 Cloudflare Worker WSS；音频走同一 WebSocket 二进制帧 |

蜂窝网直连手机↔手机 TCP/UDP 通常因 CGNAT 失败；**自动**模式会在局域网不可用时改用中继，无需手动切换。

### 自动模式怎么用

1. 两台装同一版本，**小米（有业务 SIM）= 主机**，**三星 = 伴侣**。
2. 连接方式选 **自动**（默认）。
3. 两边填**同一个 6 位配对码**（主机点「生成」后告诉伴侣）。
4. 都点「启动服务」。
5. **同一 Wi‑Fi / 热点**：伴侣先发现主机 → 走局域网（状态显示「局域网 …」）。
6. **各用流量 / 不同网**：约 5 秒内局域网不通 → 自动改云中继（状态显示「云中继 …」）。
7. 局域网中途断开：伴侣自动切中继；之后若又进同一局域网，可再升回。

主机在自动模式下会**同时**开局域网监听并挂上中继房间，伴侣从哪边来都能接到。

### 云中继配对（仅中继 / 自动备份）

中继地址默认：`https://dualphone-relay.chinatsumar33.workers.dev`。

**延迟预期：** 控制消息通常可接受；通话音频经中继往返，**经常超过 500ms**。中继会看到明文控制 JSON 与音频帧，请只用于自己的设备。

## 功能

| 功能 | Host (A) | Companion (B) |
|------|----------|---------------|
| 短信 | `SMS_RECEIVED` → 控制通道 | 系统通知；点击回复 → Host 用 SIM 发送 |
| 来电 | 检测响铃；可选自动接听；SCO / 音频 | 全屏来电页，接听/挂断指令回传 |
| 通知 | `NotificationListenerService` | 重建通知（保留 group/priority 字段） |
| 传输 | 自动：LAN 优先 / 中继备份；或仅 LAN / 仅中继 | 断线重连；可靠消息队列重放 |

## 协议摘要

控制通道每条消息为 JSON（LAN 为 TCP 一行一条；中继为 WebSocket 文本帧）。音频：`magic(0x44504155) + seq + len + PCM16LE mono 16kHz`。LAN 走 UDP `37211`；中继走 WebSocket 二进制帧。

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
- 局域网：同一 Wi‑Fi（勿用访客隔离）或热点
- 中继 / 自动备份：能访问中继 HTTPS/WSS

## 安装与配对

1. 从 [Releases](https://github.com/chinatsu033/DualPhone/releases) 下载 APK，或：
   ```bash
   ./gradlew :app:assembleDebug
   ```
2. **两台都安装同一 APK**（debug 包名 `com.dualphone.debug`）。
3. 选 **自动**（或仅局域网 / 仅云中继），填配对码，启动服务。
4. Host：申请权限 → 通知使用权 →（可选）默认拨号器。

### 部署自己的中继

```bash
cd relay
npx wrangler deploy
```

详见 [`relay/README.md`](relay/README.md)。

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
