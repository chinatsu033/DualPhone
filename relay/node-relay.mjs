/**
 * 本地/自建 Node WebSocket 中继（Durable Objects 不可用时的备选）。
 * 协议与 Cloudflare Worker 相同：/ws?room=&role=
 */
import http from "node:http";
import { WebSocketServer } from "ws";

const PORT = Number(process.env.PORT || 8787);
const rooms = new Map(); // code -> Map(role -> ws)

function statusPayload(room) {
  const roles = [...room.keys()];
  return JSON.stringify({
    type: "relay_status",
    peers: room.size,
    ready: roles.includes("host") && roles.includes("companion"),
    roles,
  });
}

function broadcastStatus(code) {
  const room = rooms.get(code);
  if (!room) return;
  const msg = statusPayload(room);
  for (const ws of room.values()) {
    if (ws.readyState === 1) ws.send(msg);
  }
}

const server = http.createServer((req, res) => {
  if (req.url === "/" || req.url?.startsWith("/health")) {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "dualphone-relay-node", version: "0.2.0" }));
    return;
  }
  res.writeHead(404);
  res.end("not found");
});

const wss = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  if (url.pathname !== "/ws") {
    socket.destroy();
    return;
  }
  const roomCode = (url.searchParams.get("room") || "").toUpperCase();
  const role = (url.searchParams.get("role") || "").toLowerCase();
  if (!/^[A-Z0-9]{6}$/.test(roomCode) || (role !== "host" && role !== "companion")) {
    socket.write("HTTP/1.1 400 Bad Request\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    if (!rooms.has(roomCode)) rooms.set(roomCode, new Map());
    const room = rooms.get(roomCode);
    const prev = room.get(role);
    if (prev && prev !== ws) {
      try {
        prev.send(JSON.stringify({ type: "relay_error", error: "replaced_by_new_connection" }));
        prev.close(4000, "replaced");
      } catch (_) {}
    }
    room.set(role, ws);
    ws.roomCode = roomCode;
    ws.role = role;
    broadcastStatus(roomCode);

    ws.on("message", (data, isBinary) => {
      for (const [r, peer] of room) {
        if (r === role) continue;
        if (peer.readyState === 1) peer.send(data, { binary: isBinary });
      }
    });
    ws.on("close", () => {
      const r = rooms.get(roomCode);
      if (r && r.get(role) === ws) r.delete(role);
      if (r && r.size === 0) rooms.delete(roomCode);
      broadcastStatus(roomCode);
    });
  });
});

server.listen(PORT, () => {
  console.log(`dualphone node relay on :${PORT}`);
});
