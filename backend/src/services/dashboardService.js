/**
 * Dashboard broadcast helpers
 */

const WebSocket = require("ws");
const { forEachClient } = require("../models/dashboardStore");
const { forEachClientSubscribedToDevice } = require("../models/dashboardStore");

function broadcastToDashboard(jsonHeader, binaryData) {
  const text = JSON.stringify(jsonHeader);
  forEachClient((client) => {
    if (client.readyState === WebSocket.OPEN) {
      client.send(text);
      if (binaryData) {
        client.send(binaryData);
      }
    }
  });
}

function broadcastToDeviceSubscribers(deviceId, jsonHeader, binaryData) {
  if (!deviceId) return;
  const text = JSON.stringify(jsonHeader);
  forEachClientSubscribedToDevice(deviceId, (client) => {
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

module.exports = {
  broadcastToDashboard,
  broadcastToDeviceSubscribers,
  sendJson,
};
