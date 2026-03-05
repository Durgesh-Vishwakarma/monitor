# WebRTC Integration (Android Device -> Dashboard)

This project now has WebRTC signaling support in:

- `server/server.js`
- `server/index.html`

Current status:

- Dashboard can create a `recvonly` WebRTC offer.
- Server relays signaling messages between dashboard and device.
- Android app now implements WebRTC audio publish and signaling handling in `MicService.kt`.
- Adaptive bitrate policy is enabled on Android (`12/24/32 kbps` based on network).

## Signaling Messages

All signaling is sent over existing WebSocket channels.

### Dashboard -> Server (`/control`)

```json
{ "cmd": "webrtc_start", "deviceId": "abc123" }
{ "cmd": "webrtc_offer", "deviceId": "abc123", "sdp": "..." }
{ "cmd": "webrtc_ice", "deviceId": "abc123", "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 } }
{ "cmd": "webrtc_stop", "deviceId": "abc123" }
```

### Server -> Device (`/audio/<deviceId>`)

```json
{ "type": "webrtc_start" }
{ "type": "webrtc_offer", "sdp": "..." }
{ "type": "webrtc_ice", "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 } }
{ "type": "webrtc_stop" }
```

### Device -> Server (`/audio/<deviceId>`)

```json
{ "type": "webrtc_answer", "sdp": "..." }
{ "type": "webrtc_ice", "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 } }
{ "type": "webrtc_state", "state": "connected" }
```

Server relays these to dashboard clients as JSON with `deviceId` attached.

## Android Implementation Notes

Use `io.github.webrtc-sdk:android` (org.webrtc API) and send mic as an audio track.

Recommended audio constraints for low bandwidth voice:

- Codec: Opus
- Start bitrate: 24 kbps
- Min bitrate: 12 kbps
- Max bitrate: 32 kbps
- Packet time: 20 ms

Practical setup:

1. Create `PeerConnectionFactory` and `PeerConnection`.
2. Capture mic with `AudioSource` + `AudioTrack`.
3. Add audio track to peer connection.
4. On `webrtc_offer`: `setRemoteDescription(offer)` -> `createAnswer()` -> `setLocalDescription(answer)` -> send `webrtc_answer`.
5. Exchange ICE candidates in both directions.
6. On `webrtc_stop`: close peer and release capturer/resources.

## Fallback Behavior

WebSocket PCM path remains available and has been tuned to reduce delay:

- Server drops frames to lagging dashboard clients when send queue grows.
- Dashboard clamps playback queue to avoid accumulating latency.

This fallback is useful until Android WebRTC is fully enabled.
