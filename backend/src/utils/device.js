/**
 * Device utilities
 */

function normalizeDeviceId(id) {
  if (!id) return "";
  return String(id).trim().replace(/[\r\n\t\0]/g, "");
}

module.exports = {
  normalizeDeviceId,
};
