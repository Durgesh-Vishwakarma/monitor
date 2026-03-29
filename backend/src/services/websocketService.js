/**
 * WebSocket server setup and heartbeat
 */

const WebSocket = require("ws");
const { parseReqUrl } = require("../utils/url");
const { WS_AUTH_TOKEN, HEARTBEAT_INTERVAL_MS } = require("../config");
const { handleAudioDevice } = require("../controllers/deviceController");
const { handleDashboard } = require("../controllers/dashboardController");

function isAuthorized(req) {
  if (!WS_AUTH_TOKEN) return true;
  const { searchParams } = parseReqUrl(req.url || "");
  const queryToken = searchParams.get("token");
  const authHeader = req.headers["authorization"] || "";
  const bearer = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : null;
  const token = req.headers["x-auth-token"] || bearer || queryToken;
  return token === WS_AUTH_TOKEN;
}

function setupWebSocketServer(httpServer) {
  const wss = new WebSocket.Server({ noServer: true, perMessageDeflate: false });

  httpServer.on("upgrade", (req, socket, head) => {
    const { pathname } = parseReqUrl(req.url || "");
    const normalizedPath = pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;
    
    const isAudioPath = normalizedPath.startsWith("/audio/");
    const isControlPath = normalizedPath === "/control";
    
    if (!isAudioPath && !isControlPath) {
      console.log(`⚠️  Unknown WS path: ${pathname}`);
      socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
      socket.destroy();
      return;
    }
    
    if (isControlPath && !isAuthorized(req)) {
      console.warn(`🔒 Unauthorized dashboard connection blocked from ${req.socket.remoteAddress}`);
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }
    
    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit("connection", ws, req);
    });
  });

  wss.on("connection", (ws, req) => {
    const { pathname } = parseReqUrl(req.url || "");
    const normalizedPath = pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;

    ws.isAlive = true;
    ws.on("pong", () => {
      ws.isAlive = true;
    });

    if (normalizedPath.startsWith("/audio/")) {
      handleAudioDevice(ws, req);
    } else if (normalizedPath === "/control") {
      handleDashboard(ws);
    } else {
      console.log(`❌ Closing WS: Unknown normalized path: ${normalizedPath}`);
      ws.close(1008, "Unknown path");
    }
  });

  const heartbeatTimer = setInterval(() => {
    wss.clients.forEach((ws) => {
      if (!ws.isAlive) {
        ws.terminate();
        return;
      }
      ws.isAlive = false;
      try {
        ws.ping();
      } catch (_) {
        ws.terminate();
      }
    });
  }, HEARTBEAT_INTERVAL_MS);

  wss.on("close", () => clearInterval(heartbeatTimer));

  return wss;
}

module.exports = {
  setupWebSocketServer,
};
