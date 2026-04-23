/**
 * Best-effort FCM wake retries for offline devices.
 *
 * Why:
 * - Devices can be offline or their WebSocket can be dead while the device still
 *   has a stale token.
 * - Single-shot wake can miss transient connectivity / throttling windows.
 *
 * This service retries until the device comes back online or max attempts is hit.
 */

const deviceStore = require("../models/deviceStore");
const { sendFcmMessage } = require("./fcmService");

const inflight = new Map(); // deviceId -> { attempts }

function scheduleWake(deviceId, opts = {}) {
  const maxAttempts = opts.maxAttempts ?? 5;
  const intervalMs = opts.intervalMs ?? 15_000;

  if (!deviceId) return false;
  const token = deviceStore.getFcmToken(deviceId);
  if (!token) return false;

  // If we already have a retry job running, don't start another.
  if (inflight.has(deviceId)) return true;

  inflight.set(deviceId, { attempts: 0 });

  const tick = async () => {
    const state = inflight.get(deviceId);
    if (!state) return;

    // Stop if device is back.
    if (deviceStore.isOnline(deviceId)) {
      inflight.delete(deviceId);
      return;
    }

    state.attempts += 1;
    const attempt = state.attempts;

    // Give the device a chance to recover; only send if we still have attempts left.
    if (attempt > maxAttempts) {
      inflight.delete(deviceId);
      return;
    }

    try {
      console.log(
        `🔔 Wake FCM attempt ${attempt}/${maxAttempts} for ${deviceId} (online=${deviceStore.isOnline(
          deviceId,
        )})`,
      );
      const success = await sendFcmMessage(token, {
        action: "force_reconnect",
        ts: Date.now(),
        attempt,
      });
      // S-L2 fix: If FCM send succeeded, give the device a moment to reconnect
      // then check — if it came back online, stop retrying immediately.
      if (success) {
        await new Promise(resolve => setTimeout(resolve, 3_000));
        if (deviceStore.isOnline(deviceId)) {
          inflight.delete(deviceId);
          return;
        }
      }
    } catch (_) {
      // Ignore individual send errors; retry loop continues.
    }

    // Re-check after interval if still offline.
    if (attempt < maxAttempts) {
      setTimeout(tick, intervalMs);
    } else {
      inflight.delete(deviceId);
    }
  };

  // Fire immediately
  void tick();
  return true;
}

module.exports = {
  scheduleWake,
};

