const fs = require("fs");
const path = require("path");
const express = require("express");
const { CLIENT_DIST_DIR, LEGACY_DASHBOARD } = require("../config");

function registerStaticRoutes(app) {
  const clientIndexHtml = path.join(CLIENT_DIST_DIR, "index.html");
  const hasClientBuild = fs.existsSync(clientIndexHtml);

  app.get("/legacy", (req, res) => {
    res.set(
      "Cache-Control",
      "no-store, no-cache, must-revalidate, proxy-revalidate",
    );
    res.set("Pragma", "no-cache");
    res.set("Expires", "0");
    res.sendFile(LEGACY_DASHBOARD);
  });

  if (hasClientBuild) {
    console.log(`📄 Serving client dashboard from ${CLIENT_DIST_DIR}`);
    app.use(express.static(CLIENT_DIST_DIR));
    app.get("*", (req, res) => {
      res.set(
        "Cache-Control",
        "no-store, no-cache, must-revalidate, proxy-revalidate",
      );
      res.set("Pragma", "no-cache");
      res.set("Expires", "0");
      res.sendFile(clientIndexHtml);
    });
  } else {
    console.warn("⚠️ Client build not found. Falling back to legacy page.");
    app.get("*", (req, res) => {
      res.set(
        "Cache-Control",
        "no-store, no-cache, must-revalidate, proxy-revalidate",
      );
      res.set("Pragma", "no-cache");
      res.set("Expires", "0");
      res.sendFile(LEGACY_DASHBOARD);
    });
  }
}

module.exports = {
  registerStaticRoutes,
};
