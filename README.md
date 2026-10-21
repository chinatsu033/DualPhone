# DualPhone

把一台安卓手机的 **短信 / 来电 / App 通知** 实时转到另一台安卓手机。

| 角色 | 中文 | 英文 | 典型机型 |
|------|------|------|----------|
| **转发端** | 有 SIM，来电与通知来源 | Host | 小米 |
| **接收端** | 显示转发过来的短信/来电/通知 | Companion | 三星 |

同一 APK，安装后按向导选角色。默认连接：**自动**（先局域网，不通再云中继）。

## 0.3.0 连接修复摘要

- 配对码：转发端生成、接收端**必须手填同一码**（不再两边各自静默生成导致进不同房间）
- 局域网发现：Wi‑Fi MulticastLock + 组播 `239.255.37.21` + 子网广播；Discover 包带配对码与本机 IP
- 接收端 TCP：无对端 IP 时**等待发现**，不再立刻失败
- 云中继：WSS 开了只算「连接中」；`relay_status.ready=true` 才显示「已连接（云中继）」
- Android 14+ 前台服务默认 `dataSync`，避免无麦克风权限时崩溃
- 新增 **连接自检**（本机 IP / UDP / TCP / WSS）
- Material 3 UI：①角色 ②配对码 ③权限 ④启动

## 快速配对（小米 → 三星）

1. 两台都装 [最新 Release APK](https://github.com/chinatsu033/DualPhone/releases)。
2. **小米 = 转发端**，点生成配对码，复制。
3. **三星 = 接收端**，粘贴同一配对码。
4. 两边连接方式保持 **自动**，点「申请权限」；小米再开「通知使用权」。
5. 两边点 **启动连接**。
6. 同一 Wi‑Fi/热点 → 状态「已连接（局域网）」；各用流量 → 「已连接（云中继）」。
7. 若都不通：点 **连接自检**，把日志发出来排查。

默认中继：`https://dualphone-relay.chinatsumar33.workers.dev`  
WSS：`wss://…/ws?room=ABC123&role=host|companion`

## 构建

```bash
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk（applicationId com.dualphone.debug）
```

## 已知限制

- 个人微信无法官方自动抓聊天；与 DualPhone 无关。
- 小米等 OEM 可能限制自动接听、通话上行采集；音频为尽力而为。
- 访客 Wi‑Fi「AP 隔离」会导致局域网失败（自动会改云中继）。
- 云中继能看到控制 JSON 与音频帧，请只用于自己的设备。

MIT License
