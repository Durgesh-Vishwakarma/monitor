/**
 * MicMonitor Server — Express + WebSocket
 * ════════════════════════════════════════════════════════════════════
 * Single port (process.env.PORT or 3000) — works on Render / Railway
 *   ws://host/audio/<deviceId>  → APK audio stream
 *   ws://host/control           → Dashboard control channel
 *   http://host/                → Static Dashboard UI (index.html)
 *
 * LOCAL:
 *   npm install && npm start
 *   Open http://localhost:3000
 *
 * RENDER DEPLOY:
 *   Push repo → connect on render.com → free Web Service
 *   Build cmd: npm install
 *   Start cmd: npm start
 * ════════════════════════════════════════════════════════════════════
 */

const WebSocket = require("ws");
const express = require("express");
const http = require("http");
const fs = require("fs");
const path = require("path");

// Load @breezystack/lamejs IIFE bundle (ES module, not CJS-compatible via require)
const _lameCode = require("fs").readFileSync(
  require("path").join(
    __dirname,
    "node_modules/@breezystack/lamejs/dist/lamejs.iife.js",
  ),
  "utf8",
);
const { Mp3Encoder } = new Function(_lameCode + "; return lamejs;")();

// ── Config ──────────────────────────────────────────────────────────
const PORT = parseInt(process.env.PORT) || 3000;
const DASHBOARD_MAX_BUFFERED_BYTES = 256 * 1024;
const WS_AUTH_TOKEN = process.env.WS_AUTH_TOKEN || "";
const DEFAULT_STUN_URL = process.env.STUN_URL || "stun:stun.l.google.com:19302";
const TURN_URL = process.env.TURN_URL || "";
const TURN_USERNAME = process.env.TURN_USERNAME || "";
const TURN_PASSWORD = process.env.TURN_PASSWORD || "";
const ICE_SERVERS = buildIceServers();

// ── Folders ─────────────────────────────────────────────────────────
const RECORDINGS_DIR = path.join(__dirname, "recordings");
if (!fs.existsSync(RECORDINGS_DIR))
  fs.mkdirSync(RECORDINGS_DIR, { recursive: true });

// ── State ─────────────────────────────────────────────────────────
/** @type {Map<string, { ws: WebSocket, model: string, sdk: number, connectedAt: Date, recordingChunks: Buffer[]|null, recordingFilename: string|null }>} */
const devices = new Map();

/** @type {Set<WebSocket>} Dashboard clients */
const dashboardClients = new Set();

// ════════════════════════════════════════════════════════════════════
// Single HTTP server — all traffic on one port
// ════════════════════════════════════════════════════════════════════
const app = express();
const httpServer = http.createServer(app);

// ── Single WebSocket server, path-based routing ──────────────────────
const wss = new WebSocket.Server({ noServer: true, perMessageDeflate: false });

httpServer.on("upgrade", (req, socket, head) => {
  const url = req.url || "";
  const isKnownWsPath = url.startsWith("/audio/") || url.startsWith("/control");
  if (!isKnownWsPath) {
    socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
    socket.destroy();
    return;
  }
  if (!isAuthorized(req)) {
    socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit("connection", ws, req);
  });
});

wss.on("connection", (ws, req) => {
  const url = req.url || "";

  if (url.startsWith("/audio/")) {
    handleAudioDevice(ws, req);
  } else if (url === "/control") {
    handleDashboard(ws);
  } else {
    ws.close(1008, "Unknown path");
  }
});

// ════════════════════════════════════════════════════════════════════
// APK Audio handler  — ws://host/audio/<deviceId>
// ════════════════════════════════════════════════════════════════════
function handleAudioDevice(ws, req) {
  const parts = (req.url || "").split("/");
  const deviceId = parts[2] || "unknown_" + Date.now();

  console.log(`📱 Device connected: ${deviceId}`);

  devices.set(deviceId, {
    ws,
    model: "Unknown",
    sdk: 0,
    connectedAt: new Date(),
    recordingChunks: null,
    recordingFilename: null,
    isStreaming: false,
    health: {
      wsConnected: true,
      micCapturing: false,
      lastAudioChunkAt: 0,
      lastHealthAt: Date.now(),
      reason: "connected",
      droppedFrames: 0,
    },
  });

  // If dashboards are already watching, start the mic immediately
  if (dashboardClients.size > 0) {
    ws.send("start_stream");
    devices.get(deviceId).isStreaming = true;
    console.log(`🎙️  Auto-sent start_stream to new device: ${deviceId}`);
  }

  // Notify dashboard
  broadcastToDashboard({
    type: "device_connected",
    deviceId,
    model: "Unknown",
    health: devices.get(deviceId)?.health,
  });

  // ── Handle incoming messages ─────────────────────────────────────
  ws.on("message", (data) => {
    // String messages = info / commands / ACKs
    // Use exact prefix matching instead of heuristic — PCM audio is always binary
    const isText =
      typeof data === "string" ||
      (data instanceof Buffer &&
        (data.slice(0, 12).toString().startsWith("DEVICE_INFO:") ||
          data.slice(0, 4).toString().startsWith("ACK:") ||
          data.slice(0, 5).toString().startsWith("FILE:") ||
          data.slice(0, 5).toString().startsWith("pong:") ||
          data.slice(0, 1).toString() === "{"));
    if (isText) {
      const text = data.toString().trim();

      if (text.startsWith("DEVICE_INFO:")) {
        // Format: DEVICE_INFO:deviceId:ModelName:sdkInt
        const [, , model, sdk] = text.split(":");
        const dev = devices.get(deviceId);
        if (dev) {
          dev.model = model;
          dev.sdk = parseInt(sdk) || 0;
        }
        console.log(`ℹ️  ${deviceId} → ${model} (SDK ${sdk})`);
        broadcastToDashboard({ type: "device_info", deviceId, model, sdk });
      } else if (text.startsWith("ACK:")) {
        console.log(`✅ ACK from ${deviceId}: ${text}`);
        broadcastToDashboard({ type: "ack", deviceId, message: text });
      } else if (text.startsWith("FILE:")) {
        const filename = text.replace("FILE:", "");
        console.log(`💾 ${deviceId} saved recording: ${filename}`);
        broadcastToDashboard({ type: "recording_saved", deviceId, filename });
      } else if (text === "pong:" + deviceId) {
        // heartbeat pong — ignore
      } else if (text.startsWith("{")) {
        // JSON payload from device (device_data, etc.)
        try {
          const json = JSON.parse(text);
          if (json.type === "device_data") {
            console.log(`📊 device_data from ${deviceId}`);
            broadcastToDashboard({ type: "device_data", deviceId, data: json.data });
          } else if (json.type === "health_status") {
            const dev = devices.get(deviceId);
            if (dev) {
              dev.health = {
                wsConnected: json.wsConnected !== false,
                micCapturing: json.micCapturing === true,
                lastAudioChunkAt: Number(json.lastAudioChunkSentAt || dev.health?.lastAudioChunkAt || 0),
                lastHealthAt: Number(json.ts || Date.now()),
                reason: String(json.reason || "heartbeat"),
                droppedFrames: Number(dev.health?.droppedFrames || 0),
              };
            }
            broadcastToDashboard({
              type: "device_health",
              deviceId,
              health: dev?.health || null,
            });
          } else if (json.type === "webrtc_answer" || json.type === "webrtc_ice" || json.type === "webrtc_state") {
            // Relay WebRTC signaling/state from phone to dashboard clients.
            broadcastToDashboard({ ...json, deviceId });
          } else if (json.type === "error") {
            console.error(`⚠️  Error from ${deviceId}: ${json.message}`);
            broadcastToDashboard({ type: "error", message: `[${deviceId.substring(0,8)}] ${json.message}` });
          } else {
            console.log(`📨 ${deviceId}: ${text}`);
          }
        } catch (_) {
          console.log(`📨 ${deviceId}: ${text}`);
        }
      } else {
        console.log(`📨 ${deviceId}: ${text}`);
      }
      return;
    }

    // Binary data = raw PCM audio chunk
    const dev = devices.get(deviceId);

    // 1) Forward chunk LIVE to all dashboard clients
    //    Pack as a single binary frame: [2-byte idLen][deviceId utf8][PCM]
    //    This avoids the JSON+binary two-message race condition on the dashboard.
    const idBuf = Buffer.from(deviceId, "utf8");
    const header = Buffer.alloc(2);
    header.writeUInt16BE(idBuf.length, 0);
    const audioFrame = Buffer.concat([header, idBuf, Buffer.from(data)]);
    if (dev?.health) {
      dev.health.lastAudioChunkAt = Date.now();
      dev.health.micCapturing = true;
      dev.health.wsConnected = true;
    }
    dashboardClients.forEach((client) => {
      if (client.readyState !== WebSocket.OPEN) return;
      // Drop frames for lagging dashboard clients to keep real-time latency low.
      if (client.bufferedAmount > DASHBOARD_MAX_BUFFERED_BYTES) {
        if (dev?.health) dev.health.droppedFrames = Number(dev.health.droppedFrames || 0) + 1;
        return;
      }
      client.send(audioFrame);
    });

    // 2) Buffer for MP3 recording if active
    if (dev && dev.recordingChunks) {
      dev.recordingChunks.push(Buffer.from(data));
    }
  });

  ws.on("close", () => {
    console.log(`❌ Device disconnected: ${deviceId}`);
    const dev = devices.get(deviceId);
    if (dev?.recordingChunks && dev.recordingChunks.length > 0) {
      saveMp3(deviceId, dev);
    }
    devices.delete(deviceId);
    broadcastToDashboard({ type: "device_disconnected", deviceId });
  });

  ws.on("error", (err) => {
    console.error(`⚠️  Error from ${deviceId}:`, err.message);
  });
}

// ════════════════════════════════════════════════════════════════════
// Dashboard handler  — ws://host/control
// ════════════════════════════════════════════════════════════════════
function handleDashboard(ws) {
  const wasEmpty = dashboardClients.size === 0;
  dashboardClients.add(ws);
  console.log("👁️  Dashboard client connected");

  // First dashboard connected — wake up all device mics
  if (wasEmpty) {
    devices.forEach((dev, id) => {
      dev.ws.send("start_stream");
      dev.isStreaming = true;
      console.log(`🎙️  start_stream → ${id}`);
    });
  }

  // Send current device list on connect
  const deviceList = [];
  devices.forEach((dev, id) => {
    deviceList.push({
      deviceId: id,
      model: dev.model,
      sdk: dev.sdk,
      connectedAt: dev.connectedAt,
      health: dev.health,
    });
  });
  ws.send(JSON.stringify({ type: "device_list", devices: deviceList }));

  ws.on("message", (data) => {
    try {
      const msg = JSON.parse(data.toString());
      const { cmd, deviceId } = msg;

      if (!deviceId || !devices.has(deviceId)) {
        ws.send(
          JSON.stringify({
            type: "error",
            message: `Device ${deviceId} not found`,
          }),
        );
        return;
      }

      const device = devices.get(deviceId);
      switch (cmd) {
        case "start_stream":
          device.ws.send("start_stream");
          device.isStreaming = true;
          broadcastToDashboard({ type: "stream_started", deviceId });
          break;
        case "stop_stream":
          stopDeviceRecording(deviceId, device);
          device.ws.send("stop_stream");
          device.isStreaming = false;
          broadcastToDashboard({ type: "stream_stopped", deviceId });
          break;
        case "start_record":
          startDeviceRecording(deviceId, device);
          device.ws.send("start_record");
          break;
        case "stop_record":
          stopDeviceRecording(deviceId, device);
          device.ws.send("stop_record");
          break;
        case "ping":
          device.ws.send("ping");
          break;
        case "get_data":
          device.ws.send("get_data");
          break;
        case "webrtc_start":
          sendJson(device.ws, { type: "webrtc_start" });
          break;
        case "webrtc_stop":
          sendJson(device.ws, { type: "webrtc_stop" });
          break;
        case "webrtc_offer":
          sendJson(device.ws, {
            type: "webrtc_offer",
            sdp: msg.sdp,
          });
          break;
        case "webrtc_ice":
          sendJson(device.ws, {
            type: "webrtc_ice",
            candidate: msg.candidate,
          });
          break;
        case "webrtc_quality":
          sendJson(device.ws, {
            type: "webrtc_quality",
            quality: msg.quality || null,
          });
          break;
        default:
          console.warn(`Unknown dashboard command: ${cmd}`);
      }
    } catch (e) {
      console.error("Dashboard message parse error:", e.message);
    }
  });

  ws.on("close", () => {
    dashboardClients.delete(ws);
    console.log("👋 Dashboard client disconnected");

    // Last dashboard left — stop all device mics
    if (dashboardClients.size === 0) {
      devices.forEach((dev, id) => {
        dev.ws.send("stop_stream");
        dev.isStreaming = false;
        console.log(`🔇  stop_stream → ${id}`);
      });
    }
  });
}

// ════════════════════════════════════════════════════════════════════
// HTTP routes
// ════════════════════════════════════════════════════════════════════

// Health check — used by Android KeepAliveWorker to wake Render from sleep
app.get("/health", (req, res) => {
  res.json({ status: "ok", devices: devices.size, ts: Date.now() });
});

// ICE config for dashboard and device clients.
app.get("/api/webrtc-config", (req, res) => {
  if (!isAuthorizedHttp(req)) {
    res.status(401).json({ error: "unauthorized" });
    return;
  }
  res.json({
    iceServers: ICE_SERVERS,
    tokenRequired: !!WS_AUTH_TOKEN,
  });
});

// List recordings as JSON
app.get("/api/recordings", (req, res) => {
  const files = fs
    .readdirSync(RECORDINGS_DIR)
    .filter((f) => f.endsWith(".mp3") || f.endsWith(".wav"))
    .map((f) => ({
      name: f,
      size: fs.statSync(path.join(RECORDINGS_DIR, f)).size,
      url: `/recordings/${f}`,
    }));
  res.json(files);
});

// Serve recording files for download
app.use("/recordings", express.static(RECORDINGS_DIR));

// All other requests → static dashboard (index.html)
app.use(express.static(path.join(__dirname)));
app.get("*", (req, res) => {
  res.sendFile(path.join(__dirname, "index.html"));
});

httpServer.listen(PORT, () => {
  console.log(`🌐 Dashboard:  http://localhost:${PORT}`);
  console.log(`🎤 Audio WS:   ws://localhost:${PORT}/audio/<deviceId>`);
  console.log(`🖥️  Control WS: ws://localhost:${PORT}/control\n`);

  // Self-ping every 14 min to prevent Render free tier from sleeping.
  // Render sleeps after 15 min of no inbound HTTP — this keeps it awake.
  const SELF_URL =
    process.env.RENDER_EXTERNAL_URL || `http://localhost:${PORT}`;
  setInterval(() => {
    http
      .get(`${SELF_URL}/health`, (r) => {
        console.log(`🔄 Self-ping: ${r.statusCode}`);
      })
      .on("error", (e) => console.warn("Self-ping error:", e.message));
  }, 14 * 60 * 1000);
});

// ════════════════════════════════════════════════════════════════════
// Helper functions
// ════════════════════════════════════════════════════════════════════

function startDeviceRecording(deviceId, device) {
  if (device.recordingChunks) return; // already recording
  device.recordingChunks = [];
  const filename = `rec_${deviceId.slice(0, 8)}_${Date.now()}.mp3`;
  device.recordingFilename = filename;
  console.log(`⏺️  Recording started: ${filename}`);
  broadcastToDashboard({ type: "recording_started", deviceId, filename });
}

function stopDeviceRecording(deviceId, device) {
  if (!device.recordingChunks || device.recordingChunks.length === 0) {
    // Nothing buffered (e.g. server restarted mid-recording) — just reset
    device.recordingChunks = null;
    device.recordingFilename = null;
    broadcastToDashboard({
      type: "recording_stopped",
      deviceId,
      filename: null,
    });
    return;
  }
  saveMp3(deviceId, device);
}

function saveMp3(deviceId, device) {
  const chunks = device.recordingChunks;
  device.recordingChunks = null;
  const filename =
    device.recordingFilename || `rec_${deviceId.slice(0, 8)}_${Date.now()}.mp3`;
  device.recordingFilename = null;

  try {
    // Encode all buffered PCM chunks → MP3
    const allPcm = Buffer.concat(chunks);
    const mp3Buffer = pcmToMp3(allPcm);
    const filepath = path.join(RECORDINGS_DIR, filename);
    fs.writeFileSync(filepath, mp3Buffer);
    console.log(
      `⏹️  Recording saved: ${filename} (${(mp3Buffer.length / 1024).toFixed(1)} KB)`,
    );
  } catch (err) {
    console.error(`❌ Failed to encode/save MP3 for ${deviceId}:`, err.message);
  }

  broadcastToDashboard({ type: "recording_stopped", deviceId, filename });
}

/**
 * Encode raw 16-bit PCM (mono, 16 kHz) → MP3 Buffer using lamejs.
 */
function pcmToMp3(pcmBuffer) {
  const encoder = new Mp3Encoder(1, 16000, 128); // mono, 16kHz, 128kbps
  const samples = new Int16Array(
    pcmBuffer.buffer,
    pcmBuffer.byteOffset,
    pcmBuffer.byteLength / 2,
  );
  const blockSize = 1152; // lamejs required block size
  const mp3Parts = [];

  for (let i = 0; i < samples.length; i += blockSize) {
    const chunk = samples.subarray(i, i + blockSize);
    const encoded = encoder.encodeBuffer(chunk);
    if (encoded.length > 0) mp3Parts.push(Buffer.from(encoded));
  }
  const flushed = encoder.flush();
  if (flushed.length > 0) mp3Parts.push(Buffer.from(flushed));

  return Buffer.concat(mp3Parts);
}

/**
 * Broadcast a JSON event (and optional binary payload) to all dashboard clients.
 * @param {object} jsonHeader  - Will be sent as text first, then binary if provided
 * @param {Buffer} [binaryData] - Optional raw audio binary
 */
function broadcastToDashboard(jsonHeader, binaryData) {
  const text = JSON.stringify(jsonHeader);
  dashboardClients.forEach((client) => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(text);
      if (binaryData) {
        client.send(binaryData);
      }
    }
  });
}

function sendJson(ws, payload) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  ws.send(JSON.stringify(payload));
}

function buildIceServers() {
  const list = [];
  if (DEFAULT_STUN_URL) {
    list.push({ urls: [DEFAULT_STUN_URL] });
  }
  if (TURN_URL && TURN_USERNAME && TURN_PASSWORD) {
    list.push({
      urls: [TURN_URL],
      username: TURN_USERNAME,
      credential: TURN_PASSWORD,
    });
  }
  return list;
}

function isAuthorized(req) {
  if (!WS_AUTH_TOKEN) return true;
  const queryToken = getQueryToken(req.url || "");
  const headerToken = req.headers["x-auth-token"];
  const provided = (queryToken || headerToken || "").toString();
  return provided === WS_AUTH_TOKEN;
}

function isAuthorizedHttp(req) {
  if (!WS_AUTH_TOKEN) return true;
  const queryToken = req.query?.token;
  const headerToken = req.headers["x-auth-token"];
  const provided = (queryToken || headerToken || "").toString();
  return provided === WS_AUTH_TOKEN;
}

function getQueryToken(url) {
  try {
    const u = new URL(url, "http://localhost");
    return u.searchParams.get("token");
  } catch (_) {
    return null;
  }
}

/**
 * Heuristic: first 200 bytes of a Buffer look like UTF-8 text?
 */
function isTextMessage(buf) {
  const sample = buf.slice(0, Math.min(200, buf.length));
  try {
    const decoded = sample.toString("utf8");
    // If it contains unprintable chars (< 0x09 except newline/tab), treat as binary
    return !/[\x00-\x08\x0B\x0C\x0E-\x1F]/.test(decoded);
  } catch (_) {
    return false;
  }
}

// ── Graceful shutdown ─────────────────────────────────────────────
process.on("SIGINT", () => {
  console.log("\n⛔ Shutting down server…");
  devices.forEach((dev) => {
    if (dev.recordingFile) dev.recordingFile.end();
  });
  process.exit(0);
});
