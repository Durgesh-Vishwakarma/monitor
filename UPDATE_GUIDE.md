# Remote Update Guide

## How to Update APK Remotely via Dashboard

### Prerequisites
1. Device must be set as Device Owner
2. Server must be accessible to the device
3. New APK must be signed with the same keystore

---

## Step-by-Step Update Process

### 1. Build the New APK

```bash
cd "c:\Users\vishw\OneDrive\Desktop\New folder\MicMonitor"
gradlew.bat assembleRelease
```

The APK will be at: `android-app\build\outputs\apk\release\app-release.apk`

---

### 2. Prepare Server Updates Folder

Create the updates folder if it doesn't exist:

```bash
mkdir updates
```

---

### 3. Copy APK to Server

Copy the built APK to the server's updates folder:

```bash
copy "android-app\build\outputs\apk\release\app-release.apk" "updates\app-release.apk"
```

---

### 4. Create/Update version.json

Create `updates\version.json` with the new version info:

```json
{
  "versionCode": 4,
  "versionName": "1.3.0",
  "apkUrl": "https://your-server.onrender.com/updates/app-release.apk",
  "changelog": "Fixed cache clear issue, added uninstall button, removed notification"
}
```

**Important:** Replace `your-server.onrender.com` with your actual Render URL!

---

### 5. Deploy to Render

If using Render, push changes to GitHub:

```bash
git add updates/
git commit -m "Update app to v1.3.0"
git push
```

Render will automatically deploy the new files.

---

### 6. Trigger Remote Update from Dashboard

1. Open your dashboard at `https://your-server.onrender.com`
2. Find the device you want to update
3. Click the **"🔄 Update App"** button
4. Confirm the update

**What happens:**
- Device checks server for new version
- Downloads APK in background
- Installs silently (no user interaction needed)
- App restarts automatically with new version
- Reconnects to dashboard

---

## Update via Dashboard Button

The dashboard now has a **"🔄 Update App"** button that:
- Sends `force_update` command to device
- Device checks `/api/version` endpoint
- Compares server version with installed version
- Downloads and installs if newer version available
- Works silently in background (Device Owner privilege)

---

## Automatic Updates

The app also checks for updates automatically every 15 minutes via WorkManager. You don't need to click the button - updates happen automatically!

---

## Version Management

Each release must have:
1. **Incremented versionCode** in `app/build.gradle.kts`
2. **Updated versionName** (semantic versioning recommended)
3. **Updated version.json** on server with matching values

Example progression:
- v1.0.0 (code 1) → Initial release
- v1.1.0 (code 2) → Auto-update system
- v1.2.0 (code 3) → Cache clear fix
- v1.3.0 (code 4) → Uninstall button + notification removal

---

## Troubleshooting

### Update Not Working?

1. **Check server logs** - Is `/api/version` endpoint working?
2. **Check version.json** - Is apkUrl correct?
3. **Check APK file** - Is it uploaded to `server/updates/`?
4. **Check device logs** - Is device downloading the APK?
5. **Check Device Owner** - Run `clear_device_owner` then re-set if needed

### Device Not Downloading?

- Make sure the device has internet
- Check if `apkUrl` in version.json is accessible
- Try clicking "🔄 Update App" button manually

### Install Failing?

- APK must be signed with SAME keystore (`micmonitor.jks`)
- Check if versionCode is actually higher than installed version
- Device Owner privilege required for silent install

---

## Current Version Info

- **Version:** 1.3.0 (versionCode: 4)
- **Changes:**
  - ✅ Fixed cache clear issue (stable device ID)
  - ✅ Added remote uninstall button
  - ✅ Removed visible notification (IMPORTANCE_MIN)
  - ✅ Added remote update button to dashboard
  - ✅ Changed app name to "Device Services"

---

## Quick Reference

**Build:** `gradlew.bat assembleRelease`  
**APK Location:** `android-app\build\outputs\apk\release\app-release.apk`  
**Copy to Server:** `copy android-app\build\outputs\apk\release\app-release.apk updates\`  
**Update version.json:** Edit `updates\version.json`  
**Dashboard Update Button:** Click "🔄 Update App" on device card  
**Auto-update:** Every 15 minutes automatically

---

## Security Notes

- Updates only work from your configured server URL
- APK signature must match (prevents malicious updates)
- Device Owner privilege required (prevents unauthorized installs)
- Update files should be served over HTTPS (Render provides this)
