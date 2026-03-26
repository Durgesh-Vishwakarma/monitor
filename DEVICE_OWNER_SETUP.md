# Device Owner Setup Guide

## 📱 Two Setup Methods

### Method 1: QR Code Provisioning (Recommended for fresh devices)
Use QR code scanning during device setup - **no USB or computer needed after initial setup!**

### Method 2: USB/ADB Setup (Traditional)
Requires computer with USB connection.

---

## 🚀 METHOD 1: QR Code Provisioning (Easiest)

This method works on **freshly factory-reset devices** without needing a computer for each device.

### Prerequisites
1. Upload `app-release.apk` to `server/updates/` folder
2. Deploy server (or run locally)
3. Open dashboard and click **"📱 Setup New Device"** button

### Steps
1. **Factory reset** the target device
2. Power ON the device
3. On the **Welcome/Start screen**, **tap 6 times quickly** anywhere on the screen
4. A **QR scanner** will appear
5. Scan the QR code from the dashboard
6. Device will:
   - Download the APK automatically
   - Install it silently
   - Set it as Device Owner
   - Grant all permissions
7. ✅ Done! Device will connect to dashboard automatically

### ⚠️ Important Notes
- Only works on **freshly reset devices** with no accounts
- QR provisioning requires Android 7.0+ (most devices support it)
- Some OEMs (Realme, Oppo, Samsung) may need you to tap in specific areas
- If QR scanner doesn't appear, try tapping faster or in different screen areas

### Alternative QR Activation Methods
| Brand | Method |
|-------|--------|
| Samsung | Tap "Emergency Call" → dial *#0*# → exits to QR scanner |
| Realme/Oppo | Tap 6 times on welcome screen |
| Xiaomi | Tap 6 times on welcome screen |
| Stock Android | Tap 6 times on welcome screen |
| Pixel | Tap 6 times on welcome screen |

---

## 🔧 METHOD 2: USB/ADB Setup (Traditional)

Use this if QR provisioning doesn't work or you need more control.

### ⚠️ Important Requirements

`adb shell dpm set-device-owner` only works when:
- ✅ Device is **factory reset** (fresh)
- ✅ **No Google account** added
- ✅ **No lock screen** set
- ✅ Your app is **already installed** via ADB

If ANY of these conditions are not met → command will fail!

### Step 1: Factory Reset Device
```
Settings → System → Reset → Factory Reset
```
Or use recovery mode (Power + Volume Down)

### Step 2: Skip Device Setup
When phone boots:
- ❌ Skip Google account
- ❌ Skip lock screen  
- ❌ Skip everything
- ✅ Go directly to home screen

### Step 3: Enable USB Debugging
```
Settings → About Phone → Tap "Build Number" 7 times
Settings → Developer Options → USB Debugging → ON
```

### Step 4: Connect & Install APK
```bash
adb devices                    # Verify connection
adb install app-release.apk    # Install your app
```

### Step 5: Set Device Owner
```bash
adb shell dpm set-device-owner com.device.services.app/com.micmonitor.app.DeviceAdminReceiver
```

Expected output:
```
Success: Device owner set to package ComponentInfo{com.device.services.app/com.micmonitor.app.DeviceAdminReceiver}
```

---

## ✅ Verify Device Owner Status
```bash
adb shell dumpsys device_policy | findstr "Device Owner"
```

Should show:
```
Device Owner: com.device.services.app
```

---

## ❌ Common Errors & Fixes

### Error: "Not allowed to set device owner"
**Cause:** Device not fresh  
**Fix:** Factory reset and skip all setup

### Error: "Device already provisioned"  
**Cause:** Google account was added during setup  
**Fix:** Factory reset, skip Google account

### Error: "Admin component not found"
**Cause:** Wrong package/receiver name  
**Fix:** Verify exact path:
```
com.device.services.app/.DeviceAdminReceiver
```

### Error: "There are already several users on the device"
**Cause:** Multiple user profiles exist  
**Fix:** Remove all secondary users or factory reset

---

## 🔓 How to Remove Device Owner (if needed)
```bash
# This fails for real Device Owner (works only for test admin)
adb shell dpm remove-active-admin com.device.services.app/com.micmonitor.app.DeviceAdminReceiver
```

For a real Device Owner, use one of these:

1) From app/dashboard command (implemented):
- Send command: `clear_device_owner`
- App calls `DevicePolicyManager.clearDeviceOwnerApp(packageName)`

2) If app is not reachable:
- Factory reset the device (guaranteed removal)

Or from app code:
```kotlin
val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
dpm.clearDeviceOwnerApp(packageName)
```

---

## 📱 What Device Owner Enables

Once set, your app can:
- ✅ Silent APK install/update (no user prompt)
- ✅ Auto-grant ALL runtime permissions
- ✅ Prevent app uninstall
- ✅ Hide from launcher
- ✅ Lock task mode (kiosk)
- ✅ Control system settings
