/**
 * Hybrid Command Routing System
 */

const { isOnline, getFcmToken, queueCommand } = require("../models/deviceStore");
const { sendJsonToDevice } = require("./deviceService");
const { sendFcmMessage } = require("./fcmService");

/**
 * intelligently routes a command to an Android device either via active WebSocket connection,
 * fallback FCM message, or queues it if both are unavailable.
 *
 * @param {string} deviceId 
 * @param {object} command payload (e.g., { type: 'take_screenshot' })
 * @returns {object} status indicating 'sent_ws', 'sent_fcm', 'queued', or 'failed'
 */
async function sendHybridCommand(deviceId, command) {
  if (!deviceId || !command) {
    return { status: "failed", error: "Missing deviceId or command" };
  }

  // 1. Try WebSocket first (Layer 1: Real-time)
  if (isOnline(deviceId)) {
    const success = sendJsonToDevice(deviceId, command);
    if (success) {
      console.log(`📤 HybridCommand: Sent '${command.type}' to ${deviceId} via WebSocket`);
      return { status: "sent_ws" };
    }
  }

  // 2. Try FCM fallback (Layer 4: Push Wake-up/Action)
  const token = getFcmToken(deviceId);
  if (token) {
    console.log(`📡 HybridCommand: WebSocket unavailable for ${deviceId}, falling back to FCM for '${command.type}'`);
    
    // We send it as a data message payload
    const success = await sendFcmMessage(token, {
      action: "remote_command",
      ...command,
      ts: Date.now()
    });
    
    if (success) return { status: "sent_fcm" };
  }

  // 3. Queue command as last resort (Layer 9: Store and Forward)
  console.log(`📥 HybridCommand: Device ${deviceId} offline and no FCM, queuing command '${command.type}'`);
  queueCommand(deviceId, command);
  return { status: "queued" };
}

module.exports = {
  sendHybridCommand,
};
