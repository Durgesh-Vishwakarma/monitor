# Testing Checklist for 40 Bug Fixes

## Audio Capture Tests

### Bug 2.1 - streamChunkSize Stability
- [ ] Start audio capture in default mode (20ms)
- [ ] Switch to low-network mode (30-40ms)  
- [ ] Verify no audio glitches or stuttering
- [ ] Check logs for "Adjusted audio frame size" messages
- **Expected:** Frame size changes cleanly without mid-capture artifacts

### Bug 2.3 - Gain Smoothing Attack/Release
- [ ] Capture loud impulsive sounds (door slam, clap)
- [ ] Verify fast response to peaks (attack = 0.25)
- [ ] Verify slow recovery from peaks (release = 0.88)
- [ ] Compare before/after audio waveforms
- **Expected:** Peaks show immediate gain reduction, smooth decay recovery

### Bug 2.4 - Spectral Denoiser Warmup
- [ ] Start audio capture with spectral denoiser enabled
- [ ] Monitor first 500ms of output
- [ ] Verify no audible artifacts or "dummy signal" in recording
- [ ] Check server logs for denoiser initialization
- **Expected:** Clean audio from first frame, no noise tail

### Bug 2.5 - Spectral Denoiser Priming
- [ ] Listen for 16ms gap or click at stream start
- [ ] Verify spectral denoiser output continuity
- [ ] Record 30 seconds and check for phase discontinuities
- **Expected:** No audible discontinuity, smooth fade-in

### Bug 2.7 - Audio Capture Stopped Flag
- [ ] Send stop_stream command
- [ ] Verify microphone stops immediately
- [ ] Check logcat for race condition warnings
- [ ] Restart service while audio is stopping
- **Expected:** No "ERROR_INVALID_OPERATION" messages

### Bug 2.8 - OkHttp Write Timeout
- [ ] Simulate slow network (throttle to 10 Kbps)
- [ ] Monitor stalled WebSocket connections
- [ ] Verify HTTP fallback triggers within 8 seconds
- [ ] Check connection recovery time
- **Expected:** Falls back to HTTP within 8s of stall, not 15s

### Bug 3.1/3.2 - Audio Focus Management
- [ ] Start audio capture
- [ ] Call an incoming phone call (or trigger via Android Emulator)
- [ ] Verify audio focus is requested before capture
- [ ] Answer call, then hang up
- [ ] Verify microphone restarts after focus regained
- **Expected:** Mic auto-restarts when focus returned, no manual intervention

### Bug 3.3 - AudioRecord Buffer Size
- [ ] Monitor CPU usage during audio capture
- [ ] Stress test with background services running
- [ ] Verify no overrun errors in logcat
- [ ] Check for "AUDIO_STATUS_UNDERFLOW" messages
- **Expected:** Buffer handles CPU bursts without underruns

### Bug 5.1 - Voice Profile Trim
- [ ] Send voice_profile command with extra spaces: `"profile": " far "`
- [ ] Verify profile set correctly (trimmed)
- [ ] Check in app preferences
- **Expected:** Profile set to "far", not " far "

### Bug 5.3 - Gain Ceiling Increase
- [ ] Set voice_profile to "far" with AI enabled
- [ ] Capture very quiet voice
- [ ] Verify gain reaches 8x (not capped at 4x)
- [ ] Compare volume with old version
- **Expected:** Far-voice significantly louder (8x possible vs 4x)

### Bug 5.5 - Hardware AGC Enable
- [ ] Set voice_profile to "far"
- [ ] Check if AutomaticGainControl is enabled
- [ ] Verify system AGC engaged (check device logs)
- [ ] Set voice_profile to "room" and verify AGC disabled
- **Expected:** AGC only active in far mode

---

## Camera & Photo Tests

### Bug 4.1 - Photo Capture Timeout
- [ ] Request photo in bright light (fast)
- [ ] Request photo in dark/night mode (slow)
- [ ] Measure total time from request to completion
- [ ] Verify timeouts are ~8s for open + 8s for session + 8s for capture
- **Expected:** Max 24s total, photos complete even in poor light

### Bug 4.2 - Night Mode Exposure
- [ ] Set photo_night to "3s"
- [ ] Capture in low light conditions
- [ ] Verify exposure time is 3 seconds (not 5)
- [ ] Compare frame time with mode setting
- **Expected:** 3s exposure respects total 15s timeout window

### Bug 4.3 - Camera Selection Fallback
- [ ] On multi-camera device, request specific camera
- [ ] Unplug/disable that camera
- [ ] Verify falls back to LOGICAL_MULTI_CAMERA
- [ ] Verify fallback produces valid JPEG
- **Expected:** Photo succeeds with fallback camera

### Bug 4.4 - Preferred Camera Facing
- [ ] Switch between front/rear cameras multiple times
- [ ] Verify preference persists correctly
- [ ] Check that next photo uses remembered preference
- [ ] Restart service and verify preference reset
- **Expected:** Preference used for current session, reset on service restart

### Bug 4.5 - Camera Live Stream Cleanup
- [ ] Start camera_live_start
- [ ] Request new camera_live_start immediately (without stop)
- [ ] Verify old stream closes before new one opens
- [ ] Check for "ERROR_CAMERA_DISABLED" in logs
- **Expected:** Proper cleanup between streams, no resource conflicts

### Bug 4.6 - Samsung SCALER_CROP_REGION
- [ ] Test on Samsung device (S21, S22, etc.)
- [ ] Request photo with full sensor capture
- [ ] Verify graceful fallback if crop unsupported
- [ ] Check logcat for "SCALER_CROP_REGION not supported"
- **Expected:** Photo succeeds even if crop region not supported

### Bug 4.7 - Camera Live Stop ACK Race
- [ ] Start camera_live_start  
- [ ] Immediately send camera_live_stop
- [ ] Verify ACK received for stop
- [ ] Check stream actually stops (no more frames)
- **Expected:** ACK received, stream cleanly stops

---

## Command & ACK Tests

### Bug 2.6 - Dashboard Redundant start_stream
- [ ] Dashboard: Click "Listen" on a device
- [ ] Wait 30 seconds with active audio stream
- [ ] Verify start_stream not sent again
- [ ] Wait 50 seconds (stale > 45s) with no audio
- [ ] Verify recovery start_stream sent once
- **Expected:** Only sent on initial request or stale recovery

### Bug 6.2 - force_reconnect ACK
- [ ] Send force_reconnect command from dashboard
- [ ] Verify ACK received before socket closes
- [ ] Check device reconnects within 10 seconds
- [ ] Verify no ACK timeout errors
- **Expected:** ACK receipt, clean reconnection

### Bug 6.3 - ACK Buffer When WS Down
- [ ] Disable network on device (Airplane mode)
- [ ] Send command from dashboard
- [ ] Re-enable network
- [ ] Verify ACK sent on reconnection
- [ ] Check server logs for queued commands
- **Expected:** ACK buffered and sent when WS restores

### Bug 6.4 - Photo ACK After Completion
- [ ] Request take_photo
- [ ] Monitor server logs for ACK timing
- [ ] Verify ACK only sent after JPEG uploaded
- [ ] Check for premature "accepted" ACK
- **Expected:** Final ACK with "success" or "error", not interim status

### Bug 6.5 - Command ACK JSON Format
- [ ] Send take_photo command
- [ ] Capture server ACK message
- [ ] Verify format is: `{"type":"command_ack","command":"take_photo"...}`
- [ ] Verify NOT: `"ACK:take_photo:busy"`
- **Expected:** Valid JSON format for all ACK messages

### Bug 6.8 - Parse Error ACK
- [ ] Send malformed JSON: `{"type": "invalid_cmd"...}`
- [ ] Verify ACK received: `{"command":"unknown","status":"error"...}`
- [ ] Check no crash in service logs
- **Expected:** Graceful error handling with ACK

---

## Backend/Network Tests

### Bug 2.2 - Server Gain Boost
- [ ] Capture far-voice with low volume
- [ ] Monitor server amplification
- [ ] Verify 1.3x gain applied (not 1.0x)
- [ ] Compare dashboard audio levels
- **Expected:** Far-voice audibly louder on dashboard

### Bug 3.4 - Low Network Mode Codec
- [ ] Enable low-network mode
- [ ] Verify codec changes from PCM16 to MuLaw
- [ ] Check for artifacts during switch
- [ ] Disable low-network mode
- [ ] Verify codec changes back
- **Expected:** Smooth codec switching, no audio glitches

### Bug 3.5 - Bandwidth Estimate Stability
- [ ] Run on VPN connection
- [ ] Monitor bitrate estimation in logs
- [ ] Verify throttling/debounce applied
- [ ] Simulate network changes
- [ ] Check no rapid oscillation in codec mode
- **Expected:** Stable bandwidth estimates, no flip-flop

### Bug 5.7 - Initial Noise Estimate
- [ ] Start audio capture with spectral denoiser
- [ ] Check initial noise level in logs
- [ ] Verify starts at -45dB (not -62dB)
- [ ] Monitor adaptive adjustment over time
- **Expected:** Better initial denoiser calibration

---

## Service Lifecycle Tests

### Bug 1.1 - WakeLock Management
- [ ] Start service
- [ ] Kill and restart (START_STICKY)
- [ ] Verify wakeLock re-acquired
- [ ] Check battery usage is reasonable
- **Expected:** Service survives restart, wakeLock proper

### Bug 1.3 - KeepAliveWorker Zombie Detection
- [ ] Network stalls with active WS (zombie state)
- [ ] Wait for keep-alive worker trigger
- [ ] Verify WS reconnection triggered
- [ ] Monitor service restart
- **Expected:** Zombie detection triggers reconnection

### Bug 1.5 - Service Destroy Cleanup
- [ ] Stop audio capture
- [ ] Immediately close service
- [ ] Monitor for exceptions in logs
- [ ] Verify all resources released
- **Expected:** Clean shutdown, no resource leaks

### Bug 1.6 - BootReceiver Consent Check
- [ ] Set consent_given = false
- [ ] Reboot device
- [ ] Verify service does NOT auto-start
- [ ] Set Device Owner status
- [ ] Reboot device
- [ ] Verify service auto-starts (DO implied)
- **Expected:** Respect consent check, auto-start on Device Owner

### Bug 1.8 - Alarm Rescheduling
- [ ] Monitor AlarmManager in Android Studio
- [ ] Verify reconnect alarm fires every 8 minutes
- [ ] Confirm alarm rescheduled in ACTION_RECONNECT handler
- [ ] Check alarm survives Doze mode
- **Expected:** Continuous alarm chain for keep-alive

### Bug 1.9 - Keep-Alive Scheduling
- [ ] Uninstall app
- [ ] Reinstall app
- [ ] Check KeepAliveWorker scheduled in onCreate
- [ ] Verify worker runs every 15 minutes
- **Expected:** Auto-scheduled without manual intervention

### Bug 1.10 - Lock Task Packages Dedup
- [ ] Set Device Owner status
- [ ] Call setLockTaskPackages multiple times
- [ ] Verify app added only once (no duplication)
- [ ] Check lock task packages list
- **Expected:** App appears once in lock task list

---

## Integration Tests

### Full Audio Pipeline
- [ ] Start audio capture in far-voice mode
- [ ] Monitor: input → HPF → EQ → denoiser → gain → output
- [ ] Verify each stage active and non-destructive
- [ ] Measure SNR improvement

### Photo Capture with Enhancement
- [ ] Take photo with AI enhancement enabled
- [ ] Verify image bright/sharp
- [ ] Take photo with night mode enabled
- [ ] Compare quality with and without enhancement

### Command Processing Under Load
- [ ] Send 10 commands rapidly
- [ ] Verify all ACK'd in order
- [ ] No command drops
- [ ] No ACK duplicates

### Network Failover
- [ ] Start on WiFi with audio capture
- [ ] Switch to cellular
- [ ] Verify seamless codec switch to MuLaw
- [ ] Switch back to WiFi
- [ ] Verify codec switch back to PCM16
- [ ] No audio gap or glitch

---

## Regression Tests (ensure no new bugs)

- [ ] Audio playback quality unchanged for room/near profiles
- [ ] Photo quality in good light unchanged
- [ ] Battery life not degraded
- [ ] Memory usage stable (no leaks)
- [ ] No new crash reports
- [ ] Dashboard responsiveness normal

---

## Performance Baselines

Record baseline metrics after all fixes:
- [ ] Audio latency: ____ ms
- [ ] Photo capture time: ____ s
- [ ] Memory usage: ____ MB
- [ ] CPU usage (idle): ____ %
- [ ] Battery drain rate: ____ % / hour
- [ ] WebSocket reconnect time: ____ s
