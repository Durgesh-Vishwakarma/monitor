const express = require("express");
const { RECORDINGS_DIR, PHOTOS_DIR } = require("../config");

function registerMediaRoutes(app) {
  app.use("/recordings", express.static(RECORDINGS_DIR));
  app.use("/photos", express.static(PHOTOS_DIR));
}

module.exports = {
  registerMediaRoutes,
};
