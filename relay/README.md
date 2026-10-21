# DualPhone cloud relay

Cloudflare Worker + Durable Object：用短配对码把 Host / Companion 的 WebSocket 帧互转（控制用文本 JSON，音频用二进制 PCM 帧）。

## 部署

```bash
cd relay
npx wrangler deploy
```

需要 Cloudflare 账号，且 API token 具备 Workers / Durable Objects 写权限。部署后得到 `https://dualphone-relay.<subdomain>.workers.dev`。

## 协议

- 连接：`wss://…/ws?room=ABC123&role=host|companion`（room 6 位大写字母数字）
- 文本帧：与 App 相同的一行 JSON 控制消息；服务端额外发 `relay_status` / `relay_error`
- 二进制帧：与局域网 UDP 相同的 `DPAU` 音频包，原样转给对端
- 每房最多 2 个角色各 1 条连接；同角色新连接会踢掉旧连接

## 本地 Node 备选

若无法用 Durable Objects，可用 `node-relay.mjs`：

```bash
node node-relay.mjs
# 默认 :8787，WSS 需自备反代
```

## 权限说明

部署需要 API Token 含 **Workers Scripts Edit**（及 Durable Objects）。若 `wrangler deploy` 报 `No access to the specified resource`，请到 Cloudflare Dashboard → My Profile → API Tokens 新建 token，或在仪表盘手动创建 Worker 后绑定本目录代码。

账号若仅有只读 token，可改用本目录 `node-relay.mjs` 自建（需公网 HTTPS/WSS 反代）。
