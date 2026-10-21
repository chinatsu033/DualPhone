/**
 * DualPhone 云中继：Worker 入口 + Durable Object 房间。
 * 文本帧互转控制 JSON；二进制帧互转音频。
 */
import { DurableObject } from "cloudflare:workers";

const ROOM_RE = /^[A-Z0-9]{6}$/;

export class RelayRoom extends DurableObject {
  async fetch(request) {
    const url = new URL(request.url);
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    const role = (url.searchParams.get("role") || "").toLowerCase();
    if (role !== "host" && role !== "companion") {
      return new Response("role must be host|companion", { status: 400 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ role });

    // 同角色只保留最新连接
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === server) continue;
      const meta = ws.deserializeAttachment() || {};
      if (meta.role === role) {
        try {
          ws.send(JSON.stringify({ type: "relay_error", error: "replaced_by_new_connection" }));
          ws.close(4000, "replaced");
        } catch (_) {}
      }
    }

    this.broadcastStatus();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, message) {
    for (const peer of this.ctx.getWebSockets()) {
      if (peer === ws) continue;
      try {
        peer.send(message);
      } catch (_) {}
    }
  }

  async webSocketClose(ws, code, reason) {
    try {
      ws.close(code, reason);
    } catch (_) {}
    this.broadcastStatus();
  }

  async webSocketError(ws) {
    try {
      ws.close(1011, "error");
    } catch (_) {}
    this.broadcastStatus();
  }

  broadcastStatus() {
    const sockets = this.ctx.getWebSockets();
    const roles = new Set();
    for (const ws of sockets) {
      const meta = ws.deserializeAttachment() || {};
      if (meta.role) roles.add(meta.role);
    }
    const payload = JSON.stringify({
      type: "relay_status",
      peers: sockets.length,
      ready: roles.has("host") && roles.has("companion"),
      roles: [...roles],
    });
    for (const ws of sockets) {
      try {
        ws.send(payload);
      } catch (_) {}
    }
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/" || url.pathname === "/health") {
      return new Response(
        JSON.stringify({
          ok: true,
          service: "dualphone-relay",
          version: "0.2.0",
          usage: "wss://<host>/ws?room=ABC123&role=host|companion",
        }),
        { headers: { "content-type": "application/json" } },
      );
    }

    if (url.pathname === "/ws") {
      const room = (url.searchParams.get("room") || "").toUpperCase();
      if (!ROOM_RE.test(room)) {
        return new Response("room must be 6 A-Z0-9", { status: 400 });
      }
      const upgrade = request.headers.get("Upgrade");
      if (!upgrade || upgrade.toLowerCase() !== "websocket") {
        return new Response("expected Upgrade: websocket", { status: 426 });
      }
      const id = env.ROOMS.idFromName(room);
      const stub = env.ROOMS.get(id);
      return stub.fetch(request);
    }

    return new Response("not found", { status: 404 });
  },
};
