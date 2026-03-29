const express = require("express");
const apiController = require("../controllers/apiController");
const { optionalAuth } = require("../middleware/auth");

const router = express.Router();

router.get("/webrtc-config", optionalAuth, apiController.webrtcConfig);
router.get("/recordings", apiController.listRecordings);
router.get("/photos", apiController.listPhotos);
router.get("/version", apiController.versionInfo);
router.post("/cache-apk-checksum", apiController.cacheApkChecksum);
router.get("/provisioning-qr", apiController.provisioningQr);

module.exports = router;
