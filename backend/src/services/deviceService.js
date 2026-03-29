/**
 * Device communication helpers
 */

const WebSocket = require("ws");
const { normalizeDeviceId } = require("../utils/device");
const { getDevice } = require("../models/deviceStore");
const { broadcastToDashboard, sendJson } = require("./dashboardService");

function sendTextToDevice(deviceId, text) {
  const normalizedId = normalizeDeviceId(deviceId);
  console.log(`📤 sendTextToDevice: "${normalizedId}" ← "${text.substring(0, 50)}"`);

  const dev = getDevice(normalizedId);
  if (!dev || !dev.ws || dev.ws.readyState !== WebSocket.OPEN) {
    console.log(`   ❌ Device "${normalizedId}" not found or offline`);
    broadcastToDashboard({
      type: "error",
      message: `Device ${normalizedId} is offline`,
    });
    return false;
  }
  try {
    dev.ws.send(text);
    console.log(`   ✅ Sent successfully`);
    return true;
  } catch (e) {
    console.log(`   ❌ Send failed: ${e.message}`);
    broadcastToDashboard({
      type: "error",
      message: `Failed to send command to ${normalizedId}: ${e.message}`,
    });
    return false;
  }
}

function sendJsonToDevice(deviceId, payload) {
  const normalizedId = normalizeDeviceId(deviceId);
  console.log(
    `📤 sendJsonToDevice: "${normalizedId}" ← ${JSON.stringify(payload).substring(0, 80)}`,
  );

  const dev = getDevice(normalizedId);
  if (!dev || !dev.ws || dev.ws.readyState !== WebSocket.OPEN) {
    console.log(`   ❌ Device "${normalizedId}" not found or offline`);
    broadcastToDashboard({
      type: "error",
      message: `Device ${normalizedId} is offline`,
    });
    return false;
  }
  try {
    sendJson(dev.ws, payload);
    console.log(`   ✅ Sent successfully`);
    return true;
  } catch (e) {
    console.log(`   ❌ Send failed: ${e.message}`);
    broadcastToDashboard({
      type: "error",
      message: `Failed to send command to ${normalizedId}: ${e.message}`,
    });
    return false;
  }
}

module.exports = {
  sendTextToDevice,
  sendJsonToDevice,
};
