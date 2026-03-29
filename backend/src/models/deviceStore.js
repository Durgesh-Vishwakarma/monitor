/**
 * Device store and selection logic
 */

const { normalizeDeviceId } = require("../utils/device");

/** @type {Map<string, any>} */
const devices = new Map();
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

function removeDevice(deviceId) {
  const normalized = normalizeDeviceId(deviceId);
  devices.delete(normalized);
  if (currentDeviceId === normalized) {
    currentDeviceId = devices.size > 0 ? devices.keys().next().value : null;
  }
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

  for (const [key, value] of devices) {
    const normalizedKey = normalizeDeviceId(key);
    if (normalizedKey === normalized) {
      return { id: key, device: value };
    }
    if (
      normalized &&
      (normalizedKey.includes(normalized) || normalized.includes(normalizedKey))
    ) {
      console.log(`📋 Fuzzy matched: "${requestedId}" → "${key}"`);
      return { id: key, device: value };
    }
  }

  if (devices.size === 1) {
    const [id, device] = devices.entries().next().value;
    console.log(`⚡ Auto-selected single device: ${id} (requested: "${requestedId}")`);
    return { id, device };
  }

  if (currentDeviceId && devices.has(currentDeviceId)) {
    console.log(`⚡ Using last connected device: ${currentDeviceId} (requested: "${requestedId}")`);
    return { id: currentDeviceId, device: devices.get(currentDeviceId) };
  }

  if (devices.size > 0) {
    const [id, device] = devices.entries().next().value;
    console.log(`⚡ Auto-selected first available: ${id} (requested: "${requestedId}")`);
    return { id, device };
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
};
