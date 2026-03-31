const express = require("express");
const path = require("path");
const multer = require("multer");
const apiController = require("../controllers/apiController");
const { optionalAuth } = require("../middleware/auth");
const { SCREENSHOTS_DIR } = require("../config");

const storage = multer.diskStorage({
  destination: function (req, file, cb) {
    cb(null, SCREENSHOTS_DIR);
  },
  filename: function (req, file, cb) {
    // Prevent path traversal by extracting just the basename
    const safeName = path.basename(file.originalname);
    cb(null, safeName);
  }
});
const upload = multer({ 
  storage,
  limits: { fileSize: 15 * 1024 * 1024 }
});

const router = express.Router();

router.get("/webrtc-config", optionalAuth, apiController.webrtcConfig);
router.get("/recordings", apiController.listRecordings);
router.get("/photos", apiController.listPhotos);
router.get("/version", apiController.versionInfo);
router.post("/cache-apk-checksum", apiController.cacheApkChecksum);
router.get("/provisioning-qr", apiController.provisioningQr);
router.get("/sync", apiController.sync);
router.post("/heartbeat", apiController.heartbeat);
router.get("/heartbeat", apiController.heartbeat);

// FCM Management
router.post("/fcm-token", apiController.saveFcmToken);
router.post("/devices/:deviceId/wake", apiController.triggerWakeUp);

// Commands & Screenshots
router.post("/devices/:deviceId/command", apiController.sendCommand);
router.get("/screenshots", apiController.listScreenshots);
router.post("/upload-screenshot", upload.single("screenshot"), apiController.uploadScreenshot);

module.exports = router;
