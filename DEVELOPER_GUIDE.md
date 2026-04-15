# Developer Quick Guide - 40 Bug Fixes

## Quick Navigation by Category

### Critical Bugs (Must Test First)
1. **Bug 2.7** - Audio capture race condition → MicService.kt:3811
2. **Bug 1.5** - Service destroy cleanup → MicService.kt:618
3. **Bug 4.1** - Photo capture timeout → MicService.kt:2335
4. **Bug 6.2** - Force reconnect ACK → MicService.kt:1091

### Audio Quality Bugs
- **Bug 2.3** - Gain smoothing → Line 3408
- **Bug 2.4** - Denoiser warmup → Line 3368
- **Bug 2.5** - Denoiser priming → Line 3566
- **Bug 5.3** - Gain ceiling → Line 3394

### Connection & Reliability
- **Bug 1.1** - WakeLock → Line 249
- **Bug 1.2** - Reconnect alarm → Line 4032
- **Bug 1.3** - Zombie detection → KeepAliveWorker.kt:34
- **Bug 1.4** - HTTP timeout → Line 904

### Camera & Photos
- **Bug 4.1** - Timeout math → Line 2335
- **Bug 4.2** - Night exposure → Line 2386
- **Bug 4.3** - Camera selection → Line 2483
- **Bug 4.5** - Stream cleanup → Line 2705
- **Bug 4.6** - Crop region → Line 2383
- **Bug 4.7** - Live stop ACK → Line 2693

### Command Handling
- **Bug 6.2** - Reconnect ACK → Line 1091
- **Bug 6.3** - ACK buffering → Line 844
- **Bug 6.4** - Photo ACK timing → Line 1607
- **Bug 6.5** - ACK format → Line 2129
- **Bug 6.8** - Error ACK → Line 1641

---

## Code Patterns to Remember

### Proper Command ACK
```kotlin
// Correct: Only ACK after operation completes
sendCommandAck("command_name", "success", "optional_detail")

// Wrong: ACK before operation
sendCommandAck("command_name")
performOperation()  // Too late!
```

### Timeout Nesting
```kotlin
// Correct: Each stage has its own timeout
withTimeoutOrNull(8_000L) { 
    // open camera
    withTimeoutOrNull(8_000L) {
        // create session
        withTimeoutOrNull(8_000L) {
            // capture
        }
    }
}

// Wrong: Single timeout for multiple operations
withTimeoutOrNull(8_000L) {
    // open + session + capture = often exceeds 8s
}
```

### WakeLock Management
```kotlin
// Correct: Reset on service restart
if (wakeLock == null) {
    acquireWakeLock()
}

// Wrong: Static wakeLock set once
companion object {
    private var wakeLock: WakeLock? = null  // Never reacquired
}
```

### Audio Focus
```kotlin
// Correct: Request and listen
am?.requestAudioFocus(
    OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AUDIOFOCUS_GAIN) {
            restartCapture()
        }
    },
    STREAM_MIC,
    AUDIOFOCUS_GAIN
)

// Wrong: No listener
// Audio focus granted but never monitored for loss/regain
```

### Spectral Denoiser Warmup
```kotlin
// Correct: Dummy buffer
if (inWarmup) {
    val dummy = DoubleArray(samples)
    spectralDenoiser.denoise(dummy)  // Don't modify work[]
} else {
    spectralDenoiser.denoise(work)   // Real processing
}

// Wrong: Real data in warmup
// Audio sounds corrupted for first few frames
spectralDenoiser.denoise(work)
```

### Gain Smoothing
```kotlin
// Correct: Fast attack, slow release
if (rawGain > smoothedGain)
    0.25 * newGain + 0.75 * oldGain  // Attack: 0.25
else
    0.88 * oldGain + 0.12 * newGain  // Release: 0.88

// Wrong: Backwards coefficients
// Slow response to peaks, fast recovery (unnatural)
if (rawGain > smoothedGain)
    0.12 * newGain + 0.88 * oldGain  // Too slow!
```

### HTTP Fallback Timing
```kotlin
// Correct: 30s delay both cases
while (isActive && delayElapsed < 30_000L && activeWebSocket == null) {
    delay(2000)
    delayElapsed += 2000
}

// Wrong: 120s when WS connected
delay(if (activeWebSocket != null) 120_000L else 30_000L)
// Waits 2 minutes for keepalive - defeats purpose
```

### Photo Cleanup
```kotlin
// Correct: Await join before restarting
try {
    oldJob?.join()
} catch (_: CancellationException) {}
startCameraLiveStream()

// Wrong: Don't wait
oldJob?.cancel()
startCameraLiveStream()  // Previous camera still opening!
```

---

## Testing Priority Matrix

```
Priority | Confidence | Bugs
---------|------------|-------
High     | Critical   | 2.7, 1.5, 4.1, 6.2
High     | Important  | 2.3, 3.1, 5.3
Medium   | Quality    | 2.4, 2.5, 4.2
Medium   | Reliability| 1.1, 1.2, 1.3
Low      | Edge Cases | 4.3, 4.6, 5.5
```

---

## Common Test Failures & Root Cause

| Symptom | Likely Bug | Check |
|---------|-----------|-------|
| Audio distorted | 2.3, 2.4, 2.5 | Log "smoothedGain", "spectralDenoiser" |
| Photo timeouts | 4.1, 4.2 | Log "captureJpegOnce failed" |
| Service crashes | 1.5, 2.7, 3.1 | Logcat exception stack |
| ACK not received | 6.2, 6.3, 6.8 | Log "sendCommandAck" |
| Zombie WebSocket | 1.3 | Log "KeepAliveWorker" |
| Battery drain | 1.1, 1.8 | Log "wakeLock", "alarm" |
| Far-voice quiet | 5.3, 5.5 | Log "gainCeil", "AGC" |
| Photo dark | 4.2 | Log "Night mode", "exposure" |

---

## Performance Tuning Hints

### For Audio Quality
- Increase `gainCeil` if far-voice still quiet (max 8.0)
- Decrease spectral denoiser strength if over-aggressive
- Adjust `smoothedGain` attack/release for responsiveness

### For Connection Stability
- Decrease `reconnectAlarmTriggerAtElapsed` for more frequent attempts
- Increase `httpFallbackDelayMs` if HTTP overloaded
- Add exponential backoff for retries

### For Camera Performance
- Reduce `maxEdge` for faster photo capture
- Increase exposure time if night mode too dark
- Add face detection for focus assistance

### For Battery Life
- Increase keepalive interval (currently 15 minutes)
- Reduce alarm frequency (currently 8 minutes)
- Disable spectral denoiser for noise-friendly environments

---

## Debugging Commands

```bash
# View audio gain logs
adb logcat | grep -i "smoothedGain\|gainCeil\|rawGain"

# View camera logs
adb logcat | grep -i "captureJpeg\|selectCamera\|Photo capture"

# View connection logs
adb logcat | grep -i "websocket\|reconnect\|http fallback"

# View ACK logs
adb logcat | grep -i "sendCommandAck\|command_ack"

# View service lifecycle
adb logcat | grep -i "onStartCommand\|onDestroy\|stopAudio"

# All bug-related logs
adb logcat | grep -i "bug\|fix"
```

---

## Code Review Checklist

When reviewing changes related to these fixes, check:

- [ ] All `withTimeoutOrNull` calls have proper timeout values
- [ ] All `sendCommandAck` calls happen after operation completion
- [ ] All `cancel()` calls are preceded by `join()` if needed
- [ ] All audio effects properly initialized before use
- [ ] All camera resources properly released (try/finally)
- [ ] All WakeLock acquisitions balanced with releases
- [ ] All JSON parsing has proper error handling
- [ ] All network operations have timeout handling
- [ ] No static mutable fields accessed from multiple threads
- [ ] No race conditions between volatile fields and their checks

---

## Regression Test Suite

Run after any audio/camera/network changes:

```bash
# Audio quality test
./scripts/test_audio_quality.sh

# Camera stability test
./scripts/test_camera_stability.sh

# Network failover test
./scripts/test_network_failover.sh

# Command ACK reliability
./scripts/test_command_acks.sh

# Memory leak detection
./scripts/test_memory_leaks.sh
```

---

## Version Markers

All fixes are marked with inline comments:

```kotlin
// Bug X.Y: Brief description
// Effect: What user will notice
```

Search for "Bug " in codebase to find all fix locations.

---

## Support Matrix

| Issue | Files | Bug IDs | Priority |
|-------|-------|---------|----------|
| Audio quality | MicService.kt | 2.1-2.8, 5.1-5.6 | High |
| Camera issues | MicService.kt, ImageEnhancer.kt | 4.1-4.7 | High |
| Connectivity | MicService.kt, KeepAliveWorker.kt | 1.1-1.4, 1.8, 3.5 | High |
| Command ACKs | MicService.kt, deviceController.js | 6.1-6.8 | High |
| Server audio | deviceController.js, audio.js | 2.2, 5.7 | Medium |
| Dashboard | dashboardController.js | 2.6 | Medium |
| Lifecycle | MicApp.kt, BootReceiver.kt | 1.5-1.10 | Medium |

---

## Contact & Escalation

For issues with specific bugs:

1. **Audio Quality** → Audio team
2. **Camera/Photos** → Camera team  
3. **Networking** → Backend team
4. **Android Lifecycle** → Platform team
5. **Dashboard** → Frontend team

Include:
- Bug ID and description
- Device model & Android version
- Full logcat output
- Steps to reproduce
- Attach before/after comparison if applicable

