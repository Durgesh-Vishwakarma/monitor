/**
 * MicMonitor Server entry point
 */

const path = require("path");

try {
  require("dotenv").config({ path: path.join(__dirname, ".env") });
} catch (_) {
  // dotenv optional
}

const { startServer } = require("./src/app");

process.on("uncaughtException", (err) => {
  console.error("🔥 Uncaught Exception:", err);
});

process.on("unhandledRejection", (reason, promise) => {
  console.error("🔥 Unhandled Rejection:", reason);
});

startServer();
