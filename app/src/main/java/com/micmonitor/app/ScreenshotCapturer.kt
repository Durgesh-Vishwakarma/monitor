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

            // Bug 11: Compositor takes time to stabilize, especially on locked screens
            delay(1500)

            var image: Image? = null
            var retries = 0
            // Poll for up to 5 seconds
            while (image == null && retries < 50) {
                image = imageReader.acquireLatestImage()
                if (image == null) delay(100)
                retries++
            }

            if (image == null) {
                Log.e(TAG, "Failed to acquire image from ImageReader after 5 seconds")
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
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            try { handlerThread?.quitSafely() } catch (_: Exception) {}
            // Bug 3: DO NOT CALL projection.stop() here. The projection must be reused.
        }
    }

    // Bug 12: Safely copy pixels avoiding BufferUnderflowException and skewed offsets
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // If rowStride perfectly matches pixelStride * width, we can copy directly
        if (rowStride == pixelStride * width) {
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }
        
        // Otherwise, copy row by row safely
        val rowBytes = width * pixelStride
        val rowArray = ByteArray(rowBytes)
        val fullBitmapArray = IntArray(width * height)

        buffer.position(0)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(rowArray, 0, rowBytes)
            
            for (x in 0 until width) {
                val offset = x * pixelStride
                val r = rowArray[offset].toInt() and 0xFF
                val g = rowArray[offset + 1].toInt() and 0xFF
                val b = rowArray[offset + 2].toInt() and 0xFF
                val a = rowArray[offset + 3].toInt() and 0xFF
                // Pack into ARGB_8888
                fullBitmapArray[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        bitmap.setPixels(fullBitmapArray, 0, width, 0, 0, width, height)
        return bitmap
    }
}
