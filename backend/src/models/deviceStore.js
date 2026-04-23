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
  const queue = pendingCommands.get(norm);
  queue.push({ command, ts: Date.now() });
  
  // Bug Fix: Prevent infinite memory leak if offline device is spammed with commands
  const maxPendingCommands = 100;
  if (queue.length > maxPendingCommands) {
    queue.shift();
  }
}

function popQueuedCommands(deviceId) {
  const norm = normalizeDeviceId(deviceId);
  const now = Date.now();
  const claim = pendingCommandClaims.get(norm);

  // S-M3 fix: Reduced idempotency window from 2s to 500ms.
  // The 2s window caused command loss: rapid device reconnect → re-sync within 2s
  // → replayed=true → device skips commands → commands silently dropped.
  if (claim && now - claim.claimedAt <= 500) {
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
  }, 500);

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
};
