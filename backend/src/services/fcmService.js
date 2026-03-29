/**
 * Service to send FCM messages using the Legacy HTTP API.
 */

const { FCM_SERVER_KEY } = require("../config");

/**
 * Sends an FCM message to a specific device token.
 * 
 * @param {string} token The recipient's FCM registration token.
 * @param {object} data The data payload to send.
 * @returns {Promise<boolean>} True if sent successfully.
 */
async function sendFcmMessage(token, data) {
  if (!FCM_SERVER_KEY || FCM_SERVER_KEY === "your_fcm_server_key_here") {
    console.warn("⚠️  FCM_SERVER_KEY not configured. Cannot send wake-up signal.");
    return false;
  }

  if (!token) {
    console.error("❌ Cannot send FCM: No token provided.");
    return false;
  }

  try {
    console.log(`📡 Sending FCM to device token: ${token.substring(0, 10)}...`);
    
    const response = await fetch("https://fcm.googleapis.com/fcm/send", {
      method: "POST",
      headers: {
        "Authorization": `key=${FCM_SERVER_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        to: token,
        priority: "high", // Critical for waking from Doze
        data: data,
      }),
    });

    const result = await response.json();
    
    if (response.ok && result.success === 1) {
      console.log(`✅ FCM Message sent successfully. Message ID: ${result.results[0].message_id}`);
      return true;
    } else {
      console.error(`❌ FCM Message failed: ${JSON.stringify(result)}`);
      return false;
    }
  } catch (e) {
    console.error(`❌ FCM Fetch error: ${e.message}`);
    return false;
  }
}

module.exports = {
  sendFcmMessage,
};
