const express = require("express");
const path = require("path");

function registerVendorRoutes(app) {
  app.use(
    "/vendor/web-noise-suppressor",
    express.static(
      path.join(
        __dirname,
        "..",
        "..",
        "node_modules",
        "@sapphi-red",
        "web-noise-suppressor",
        "dist",
      ),
    ),
  );

  const qrcodeBuildDir = path.join(
    path.dirname(require.resolve("qrcode/package.json")),
    "build",
  );
  app.use("/vendor/qrcode", express.static(qrcodeBuildDir));
}

module.exports = {
  registerVendorRoutes,
};
