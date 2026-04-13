/**
 * Service to send FCM messages.
 * Prefers FCM HTTP v1 (OAuth2), falls back to legacy API if explicitly configured.
 */

const { GoogleAuth } = require("google-auth-library");
const { FCM_SERVER_KEY, FCM_PROJECT_ID, FCM_SERVICE_ACCOUNT_JSON } = require("../config");

let cachedGoogleAuth = null;

function getGoogleAuth() {
  if (cachedGoogleAuth) return cachedGoogleAuth;

  const options = {
    scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
  };

  if (FCM_SERVICE_ACCOUNT_JSON) {
    try {
      const parsed = JSON.parse(FCM_SERVICE_ACCOUNT_JSON);
      options.credentials = parsed;
      if (parsed?.type && parsed.type !== "service_account") {
        console.warn(`⚠️  FCM credentials type is "${parsed.type}", expected "service_account".`);
      }
    } catch (e) {
      console.error("❌ Invalid FCM_SERVICE_ACCOUNT_JSON:", e.message);
    }
  }

  cachedGoogleAuth = new GoogleAuth(options);
  return cachedGoogleAuth;
}

async function sendViaFcmV1(token, data) {
  if (!FCM_PROJECT_ID) return { attempted: false, success: false, reason: "missing_project_id" };

  const auth = getGoogleAuth();
  const client = await auth.getClient();
  const accessToken = await client.getAccessToken();
  const bearer = typeof accessToken === "string" ? accessToken : accessToken?.token;

  if (!bearer) {
    return { attempted: true, success: false, reason: "missing_access_token" };
  }

  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${FCM_PROJECT_ID}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${bearer}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          data,
          android: { priority: "high" },
        },
      }),
    },
  );

  const raw = await response.text();
  let parsed = null;
  try {
    parsed = raw ? JSON.parse(raw) : null;
  } catch (_e) {
    parsed = raw;
  }

  if (!response.ok) {
    if (response.status === 401) {
      console.warn("⚠️  FCM v1 unauthorized (401). Keeping cached GoogleAuth to avoid token endpoint hammering.");
    }
    return {
      attempted: true,
      success: false,
      reason: `http_${response.status}`,
      detail: parsed,
    };
  }

  return { attempted: true, success: true, detail: parsed };
}

async function sendViaLegacy(token, data) {
  if (!FCM_SERVER_KEY || FCM_SERVER_KEY === "your_fcm_server_key_here") {
    return { attempted: false, success: false, reason: "missing_legacy_key" };
  }

  const response = await fetch("https://fcm.googleapis.com/fcm/send", {
    method: "POST",
    headers: {
      Authorization: `key=${FCM_SERVER_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      to: token,
      priority: "high",
      data,
    }),
  });

  const result = await response.json();
  const success = response.ok && result.success === 1;
  return {
    attempted: true,
    success,
    reason: success ? null : `legacy_${response.status}`,
    detail: result,
  };
}

/**
 * Sends an FCM message to a specific device token.
 * 
 * @param {string} token The recipient's FCM registration token.
 * @param {object} data The data payload to send.
 * @returns {Promise<boolean>} True if sent successfully.
 */
async function sendFcmMessage(token, data) {
  if (!token) {
    console.error("❌ Cannot send FCM: No token provided.");
    return false;
  }

  try {
    console.log(`📡 Sending FCM to device token: ${token.substring(0, 10)}...`);

    // Prefer HTTP v1 (supported for current Firebase projects).
    const v1 = await sendViaFcmV1(token, data);
    if (v1.attempted && v1.success) {
      console.log(`✅ FCM v1 message sent successfully.`);
      return true;
    }

    if (v1.attempted && !v1.success) {
      console.error(`❌ FCM v1 failed: ${JSON.stringify(v1.detail || v1.reason)}`);
    }

    // Optional fallback for older deployments still using legacy key.
    const legacy = await sendViaLegacy(token, data);
    if (legacy.attempted && legacy.success) {
      console.warn("⚠️  FCM sent via deprecated legacy API. Migrate to HTTP v1.");
      return true;
    }

    if (legacy.attempted && !legacy.success) {
      console.error(`❌ FCM legacy failed: ${JSON.stringify(legacy.detail || legacy.reason)}`);
    }

    if (!v1.attempted && !legacy.attempted) {
      console.error(
        "❌ FCM not configured. Set FCM_PROJECT_ID + service account (recommended) or FCM_SERVER_KEY (legacy).",
      );
    }

    return false;
  } catch (e) {
    console.error(`❌ FCM Fetch error: ${e.message}`);
    return false;
  }
}

module.exports = {
  sendFcmMessage,
};
