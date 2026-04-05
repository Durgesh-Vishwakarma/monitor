/**
 * Server configuration and paths.
 */

const path = require("path");

module.exports = {
  PORT: parseInt(process.env.PORT, 10) || 3000,
  NODE_ENV: process.env.NODE_ENV || "development",
  DASHBOARD_MAX_BUFFERED_BYTES: 96 * 1024,
  HEARTBEAT_INTERVAL_MS: 15_000,
  SELF_PING_INTERVAL_MS: 14 * 60 * 1000,
  WS_AUTH_TOKEN: process.env.WS_AUTH_TOKEN || null,
  FCM_SERVER_KEY: process.env.FCM_SERVER_KEY || null,
  RECORDINGS_DIR: path.join(__dirname, "..", "..", "recordings"),
  PHOTOS_DIR: path.join(__dirname, "..", "..", "photos"),
  UPDATES_DIR: path.resolve(__dirname, "..", "..", "..", "updates"),
  CLIENT_DIST_DIR: path.resolve(__dirname, "..", "..", "..", "client", "dist"),
  LEGACY_DASHBOARD: path.join(__dirname, "..", "..", "index.html"),
};
