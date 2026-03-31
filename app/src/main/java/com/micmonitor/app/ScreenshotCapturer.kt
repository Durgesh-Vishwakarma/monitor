package com.micmonitor.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Handles MediaProjection screenshot capture and uploading.
 */
object ScreenshotCapturer {
    private const val TAG = "ScreenshotCapturer"

    /**
     * Captures a screenshot and uploads it to the backend.
     * @param context required for system services
     * @param projection The active MediaProjection instance
     * @param serverHttpBaseUrl The target backend URL
     * @param deviceId the unique ID string to use in the filename
     * @param client OkHttpClient instance to reuse
     */
    @SuppressLint("WrongConstant")
    suspend fun captureAndUpload(
        context: Context,
        projection: MediaProjection?,
        serverHttpBaseUrl: String,
        deviceId: String,
        client: OkHttpClient
    ): Boolean = withContext(Dispatchers.IO) {
        if (projection == null) {
            Log.e(TAG, "No active MediaProjection provided")
            return@withContext false
        }

        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null
        var handlerThread: HandlerThread? = null

        try {
            // Bug 1: Create a dedicated HandlerThread with a Looper for ImageReader callbacks
            handlerThread = HandlerThread("ScreenshotThread").apply { start() }
            val handler = Handler(handlerThread.looper)

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                metrics.densityDpi = context.resources.configuration.densityDpi
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
            }

            // Using RGBA_8888 because it's required for Display capture
            imageReader = ImageReader.newInstance(
                metrics.widthPixels, 
                metrics.heightPixels, 
                PixelFormat.RGBA_8888, 
                2
            )

            virtualDisplay = projection.createVirtualDisplay(
                "ScreenshotCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler // Use handler thread so callbacks fire correctly
            )

            var image: Image? = null
            val start = System.currentTimeMillis()

            while (System.currentTimeMillis() - start < 5000) {
                image = imageReader.acquireLatestImage()
                if (image != null) break
                Thread.sleep(100)
            }

            if (image == null) {
                Log.e(TAG, "Failed to capture screenshot (timeout)")
                return@withContext false
            }

            val bitmap = imageToBitmap(image)
            image.close()

            if (bitmap == null) return@withContext false

            val baos = ByteArrayOutputStream()
            // Compress quality 70 to save bandwidth
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val jpegData = baos.toByteArray()
            bitmap.recycle()

            val filename = "screenshot_${deviceId}_${System.currentTimeMillis()}.jpg"
            Log.i(TAG, "Uploading screenshot: $filename (${jpegData.size} bytes)")

            // Bug 13 & 23: Use MultipartBody to safely pass binary and allow req.body access in Express
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("deviceId", deviceId)
                .addFormDataPart(
                    "screenshot",
                    filename,
                    jpegData.toRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url("$serverHttpBaseUrl/api/upload-screenshot")
                .post(requestBody)
                // Keep falling back to headers just in case
                .addHeader("X-Filename", filename)
                .addHeader("X-Device-Id", deviceId)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()

            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed: ${e.message}", e)
            return@withContext false
        } finally {
            try { imageReader?.close() } catch (_: Exception) {}
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { handlerThread?.quitSafely() } catch (_: Exception) {}
            // Media projection is kept alive and managed by MicService.
        }
    }

    // Bug 12: Safely copy pixels avoiding OOM with padded strides
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width

        var bitmap: Bitmap? = null
        var finalBitmap: Bitmap? = null
        
        try {
            // Allocate a bitmap that includes the padding area
            bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )

            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual size (removes padding gap)
            finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            
            return finalBitmap
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap failed: ${e.message}")
            return null
        } finally {
            if (bitmap != null && bitmap !== finalBitmap) {
                bitmap.recycle()
            }
        }
    }
}
