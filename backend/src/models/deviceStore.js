/**
 * Device store and selection logic
 */

const { normalizeDeviceId } = require("../utils/device");

const fs = require("fs");
const path = require("path");

/** @type {Map<string, any>} */
const devices = new Map();
const pendingCommands = new Map(); // deviceId -> [{cmd, timestamp}]
const pendingCommandClaims = new Map(); // deviceId -> { commands, claimedAt, generation }
const sessionStates = new Map(); // deviceId -> last known state
const offlineStats = new Map(); // deviceId -> { lastSeen: timestamp }
const fcmTokens = new Map(); // deviceId -> token

const TOKENS_PATH = path.join(__dirname, "..", "..", "fcm_tokens.json");

// Load tokens on startup
try {
  if (fs.existsSync(TOKENS_PATH)) {
    const data = JSON.parse(fs.readFileSync(TOKENS_PATH, "utf8"));
    Object.entries(data).forEach(([id, token]) => fcmTokens.set(id, token));
    console.log(`📦 Loaded ${fcmTokens.size} persistent FCM tokens`);
  }
} catch (e) {
  console.error("Error loading fcm_tokens.json:", e.message);
}

let currentDeviceId = null;

function setCurrentDeviceId(deviceId) {
  currentDeviceId = deviceId || null;
}

function getCurrentDeviceId() {
  return currentDeviceId;
}

function addDevice(deviceId, device) {
  const normalized = normalizeDeviceId(deviceId);
  devices.set(normalized, device);
  currentDeviceId = normalized;
  return normalized;
}

function getDevice(deviceId) {
  return devices.get(normalizeDeviceId(deviceId));
}

function hasDevice(deviceId) {
  return devices.has(normalizeDeviceId(deviceId));
}

function isOnline(deviceId) {
  return devices.has(normalizeDeviceId(deviceId));
}

function getOfflineStats(deviceId) {
  return offlineStats.get(normalizeDeviceId(deviceId)) || null;
}

function removeDevice(deviceId) {
  const normalized = normalizeDeviceId(deviceId);
  if (devices.has(normalized)) {
    offlineStats.set(normalized, { lastSeen: Date.now() });
  }
  devices.delete(normalized);
  if (currentDeviceId === normalized) {
    currentDeviceId = devices.size > 0 ? devices.keys().next().value : null;
  }
}

// ── Layer 9 & 10: Command Queue & Session Restore ──

function queueCommand(deviceId, command) {
  const norm = normalizeDeviceId(deviceId);
  if (!pendingCommands.has(norm)) {
    pendingCommands.set(norm, []);
  }
  pendingCommands.get(norm).push({ command, ts: Date.now() });
}

function popQueuedCommands(deviceId) {
  const norm = normalizeDeviceId(deviceId);
  const now = Date.now();
  const claim = pendingCommandClaims.get(norm);

  // Return the same claimed snapshot for quick duplicate sync polls (idempotent replay).
  if (claim && now - claim.claimedAt <= 2_000) {
    return {
      commands: claim.commands.map((c) => c.command),
      generation: claim.generation,
      replayed: true,
    };
  }

  const commands = pendingCommands.get(norm) || [];
  pendingCommands.delete(norm);

  const generation = now;
  pendingCommandClaims.set(norm, { commands, claimedAt: now, generation });
  setTimeout(() => {
    const active = pendingCommandClaims.get(norm);
    if (active && active.generation === generation) {
      pendingCommandClaims.delete(norm);
    }
  }, 2_000);

  return {
    commands: commands.map((c) => c.command),
    generation,
    replayed: false,
  };
}

function peekQueuedCommands(deviceId) {
  const norm = normalizeDeviceId(deviceId);
  const commands = pendingCommands.get(norm) || [];
  return commands.map((c) => c.command);
}

function queuedCommandCount(deviceId) {
  const norm = normalizeDeviceId(deviceId);
  return (pendingCommands.get(norm) || []).length;
}

function saveSessionState(deviceId, stateObj) {
  const norm = normalizeDeviceId(deviceId);
  const existing = sessionStates.get(norm) || {};
  sessionStates.set(norm, { ...existing, ...stateObj });
}

function getSessionState(deviceId) {
  return sessionStates.get(normalizeDeviceId(deviceId)) || {};
}

function updateHeartbeat(deviceId) {
  const norm = normalizeDeviceId(deviceId);
  offlineStats.set(norm, { lastSeen: Date.now() });
}

function listDevices() {
  const list = [];
  devices.forEach((dev, id) => {
    list.push({
      deviceId: id,
      model: dev.model,
      sdk: dev.sdk,
      appVersionName: dev.appVersionName || "",
      appVersionCode: Number(dev.appVersionCode || 0),
      connectedAt: dev.connectedAt,
      health: dev.health,
    });
  });
  return list;
}

function findDevice(requestedId) {
  const normalized = normalizeDeviceId(requestedId);

  if (normalized && devices.has(normalized)) {
    return { id: normalized, device: devices.get(normalized) };
  }

  return null;
}

function forEachDevice(callback) {
  devices.forEach(callback);
}

function size() {
  return devices.size;
}

function saveFcmToken(deviceId, token) {
  const norm = normalizeDeviceId(deviceId);
  if (fcmTokens.get(norm) === token) return;
  fcmTokens.set(norm, token);
  scheduleTokenFlush();
}

let tokenFlushTimer = null;
function scheduleTokenFlush() {
  if (tokenFlushTimer) return;
  tokenFlushTimer = setTimeout(() => {
    tokenFlushTimer = null;
    try {
      const data = {};
      fcmTokens.forEach((v, k) => {
        data[k] = v;
      });
      fs.writeFile(TOKENS_PATH, JSON.stringify(data, null, 2), (err) => {
        if (err) {
          console.error("Error saving fcm_tokens.json:", err.message);
        }
      });
    } catch (e) {
      console.error("Error preparing fcm_tokens.json:", e.message);
    }
  }, 5_000);
}

function getFcmToken(deviceId) {
  return fcmTokens.get(normalizeDeviceId(deviceId)) || null;
}

module.exports = {
  addDevice,
  getDevice,
  hasDevice,
  removeDevice,
  listDevices,
  findDevice,
  forEachDevice,
  size,
  setCurrentDeviceId,
  getCurrentDeviceId,
  devices,
  queueCommand,
  popQueuedCommands,
  peekQueuedCommands,
  queuedCommandCount,
  saveSessionState,
  getSessionState,
  isOnline,
  getOfflineStats,
  updateHeartbeat,
  saveFcmToken,
  getFcmToken,
};
