/**
 * Command Store (Layer 9 & 10)
 * Manages pending commands for devices that are temporarily offline or unable to receive commands via WebSocket.
 */

const { normalizeDeviceId } = require("../utils/device");

/** @type {Map<string, Array<any>>} */
const pendingCommands = new Map();

/**
 * Add a command to the device's pending queue.
 * @param {string} deviceId 
 * @param {any} command 
 */
function enqueueCommand(deviceId, command) {
  const normalized = normalizeDeviceId(deviceId);
  if (!normalized) return;

  if (!pendingCommands.has(normalized)) {
    pendingCommands.set(normalized, []);
  }
  
  const queue = pendingCommands.get(normalized);
  
  // Prevent duplicate commands if they are identical (e.g. multiple "start_stream" requests)
  const isDuplicate = queue.some(c => JSON.stringify(c) === JSON.stringify(command));
  if (!isDuplicate) {
    queue.push(command);
    console.log(`📥 Queued command for offline device ${normalized}:`, command.type || command);
  }
}

/**
 * Retrieve all pending commands for a device and clear the queue.
 * @param {string} deviceId 
 * @returns {Array<any>} List of pending commands
 */
function dequeueCommands(deviceId) {
  const normalized = normalizeDeviceId(deviceId);
  if (!normalized || !pendingCommands.has(normalized)) {
    return [];
  }

  const commands = pendingCommands.get(normalized);
  pendingCommands.delete(normalized);
  
  if (commands.length > 0) {
    console.log(`📤 Drained ${commands.length} pending commands for ${normalized}`);
  }
  
  return commands;
}

/**
 * Check if a device has pending commands without clearing them.
 * @param {string} deviceId 
 * @returns {boolean}
 */
function hasPendingCommands(deviceId) {
  const normalized = normalizeDeviceId(deviceId);
  const queue = pendingCommands.get(normalized);
  return queue && queue.length > 0;
}

/**
 * Clear the queue for a specific device.
 * @param {string} deviceId 
 */
function clearQueue(deviceId) {
  const normalized = normalizeDeviceId(deviceId);
  pendingCommands.delete(normalized);
}

module.exports = {
  enqueueCommand,
  dequeueCommands,
  hasPendingCommands,
  clearQueue
};
