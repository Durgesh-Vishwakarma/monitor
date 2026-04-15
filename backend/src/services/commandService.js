/**
 * Hybrid Command Routing System
 */

const { isOnline, getFcmToken, queueCommand } = require("../models/deviceStore");
const { sendJsonToDevice } = require("./deviceService");
const { sendFcmMessage } = require("./fcmService");
const { broadcastToDashboard } = require("./dashboardService");

/**
 * intelligently routes a command to an Android device either via active WebSocket connection,
 * fallback FCM message, or queues it if both are unavailable.
 *
 * @param {string} deviceId 
 * @param {object} command payload (e.g., { type: 'take_photo' })
 * @returns {object} status indicating 'sent_ws', 'sent_fcm_queued', 'queued', or 'failed'
 */
function serializeQueuedCommand(command) {
  if (typeof command === "string") return command;
  if (!command || typeof command !== "object") return String(command || "");

  const type = typeof command.type === "string" ? command.type.trim() : "";
  if (!type) {
    try {
      return JSON.stringify(command);
    } catch (_) {
      return "";
    }
  }

  const hasExtraFields = Object.keys(command).some(
    (key) => key !== "type" && typeof command[key] !== "undefined",
  );

  if (!hasExtraFields) return type;

  try {
    return JSON.stringify({ ...command, type });
  } catch (_) {
    return type;
  }
}

async function sendHybridCommand(deviceId, command) {
  if (!deviceId || !command) {
    return { status: "failed", error: "Missing deviceId or command" };
  }

  // 1. Try WebSocket first (Layer 1: Real-time)
  if (isOnline(deviceId)) {
    const success = sendJsonToDevice(deviceId, command);
    if (success) {
      console.log(`📤 HybridCommand: Sent '${command.type}' to ${deviceId} via WebSocket`);
      const result = { status: "sent_ws" };
      broadcastToDashboard({
        type: "command_pending",
        deviceId,
        command: String(command.type || ""),
        route: "ws",
        ts: Date.now(),
      });
      return result;
    }
  }

  // App sync fallback expects command strings (plain command or JSON string).
  const queuedCommand = serializeQueuedCommand(command);
  if (!queuedCommand) {
    return { status: "failed", error: "Command serialization failed" };
  }

  queueCommand(deviceId, queuedCommand);
  console.log(
    `📥 HybridCommand: Queued '${command.type}' for ${deviceId} (ws unavailable)`
  );
  broadcastToDashboard({
    type: "command_pending",
    deviceId,
    command: String(command.type || ""),
    route: "queue",
    ts: Date.now(),
  });

  // 2. Try FCM wake-up so the app reconnects and drains queued commands.
  const token = getFcmToken(deviceId);
  if (token) {
    console.log(
      `📡 HybridCommand: Sending wake-up FCM for queued '${command.type}' -> ${deviceId}`
    );
    
    const success = await sendFcmMessage(token, {
      action: "force_reconnect",
      type: "force_reconnect",
      commandType: String(command.type || ""),
      ts: Date.now()
    });
    
    if (success) {
      return { status: "sent_fcm_queued" };
    }

    return {
      status: "queued",
      warning: "fcm_send_failed",
    };
  }

  // 3. No FCM token available - command stays queued for HTTP sync poll.
  console.log(`📥 HybridCommand: No FCM token for ${deviceId}, command '${command.type}' remains queued`);
  return { status: "queued" };
}

module.exports = {
  sendHybridCommand,
};
