/**
 * WebRTC ICE server configuration
 */

const DEFAULT_STUN_URL = process.env.STUN_URL || "stun:stun.l.google.com:19302";

const FALLBACK_STUN_URLS = [
  DEFAULT_STUN_URL,
  "stun:stun1.l.google.com:19302",
  "stun:stun2.l.google.com:19302",
];

const ICE_SERVERS = [{ urls: FALLBACK_STUN_URLS }];

if (process.env.TURN_URL) {
  ICE_SERVERS.push({
    urls: [process.env.TURN_URL],
    username: process.env.TURN_USERNAME || "",
    credential: process.env.TURN_CREDENTIAL || "",
  });
}

module.exports = {
  DEFAULT_STUN_URL,
  FALLBACK_STUN_URLS,
  ICE_SERVERS,
};
