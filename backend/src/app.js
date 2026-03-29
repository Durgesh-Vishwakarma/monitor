/**
 * Express app setup and server start
 */

const express = require("express");
const cors = require("cors");
const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");
const {
  PORT,
  RECORDINGS_DIR,
  PHOTOS_DIR,
  UPDATES_DIR,
  SELF_PING_INTERVAL_MS,
} = require("./config");
const apiController = require("./controllers/apiController");
const apiRoutes = require("./routes/apiRoutes");
const { registerMediaRoutes } = require("./routes/mediaRoutes");
const { registerVendorRoutes } = require("./routes/vendorRoutes");
const { registerStaticRoutes } = require("./routes/staticRoutes");
const { errorHandler } = require("./middleware/errorHandler");
const { setupWebSocketServer } = require("./services/websocketService");
const { startStreamRecovery } = require("./controllers/dashboardController");

function ensureDir(dirPath) {
  if (!fs.existsSync(dirPath)) {
    fs.mkdirSync(dirPath, { recursive: true });
  }
}

function createApp() {
  ensureDir(RECORDINGS_DIR);
  ensureDir(PHOTOS_DIR);
  ensureDir(UPDATES_DIR);

  const app = express();
  
  // CORS configuration for Vercel frontend
  app.use(cors({
    origin: [
      'http://localhost:5173',
      'http://localhost:3000',
      /\.vercel\.app$/,
      /^https:\/\/.*\.vercel\.app$/
    ],
    credentials: true
  }));
  
  app.use(express.json({ limit: "10mb" }));

  app.get("/health", apiController.health);

  app.use("/api", apiRoutes);

  app.use(
    "/updates",
    express.static(UPDATES_DIR, {
      etag: false,
      setHeaders: (res, filePath) => {
        const base = path.basename(filePath).toLowerCase();
        if (base === "app-release.apk" || base === "version.json") {
          res.setHeader(
            "Cache-Control",
            "no-store, no-cache, must-revalidate, proxy-revalidate",
          );
          res.setHeader("Pragma", "no-cache");
          res.setHeader("Expires", "0");
        }
      },
    }),
  );

  registerMediaRoutes(app);
  registerVendorRoutes(app);
  registerStaticRoutes(app);

  app.use(errorHandler);

  return app;
}

function startServer() {
  const app = createApp();
  const httpServer = http.createServer(app);
  setupWebSocketServer(httpServer);
  startStreamRecovery();

  httpServer.listen(PORT, () => {
    console.log(`🌐 Dashboard:  http://localhost:${PORT}`);
    console.log(`🎤 Audio WS:   ws://localhost:${PORT}/audio/<deviceId>`);
    console.log(`🖥️  Control WS: ws://localhost:${PORT}/control\n`);

    const selfUrl =
      process.env.RENDER_EXTERNAL_URL || `http://localhost:${PORT}`;

    setInterval(() => {
      const parsedUrl = new URL(selfUrl);
      const protocol = parsedUrl.protocol === "https:" ? https : http;
      protocol
        .get(`${selfUrl}/health`, (r) => {
          console.log(`🔄 Self-ping: ${r.statusCode}`);
        })
        .on("error", (e) => console.warn("Self-ping error:", e.message));
    }, SELF_PING_INTERVAL_MS);
  });

  return httpServer;
}

module.exports = {
  createApp,
  startServer,
};
