package com.micmonitor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Professional image enhancement pipeline for photo capture.
 * Provides: auto-brightness, night mode enhancement, face detection/enhancement,
 * denoising, sharpening, and network-aware compression.
 */
object ImageEnhancer {
    private const val TAG = "ImageEnhancer"

    /** Capture mode based on lighting conditions */
    enum class CaptureMode {
        FAST,   // Good light - minimal processing
        SMART,  // Normal - balanced processing
        NIGHT   // Low light - full enhancement pipeline
    }

    /**
     * Detect optimal capture mode based on image brightness.
     * @param avgLuma Average luminance (0-255)
     * @return Recommended capture mode
     */
    fun detectMode(avgLuma: Float): CaptureMode {
        return when {
            avgLuma < 50f -> CaptureMode.NIGHT   // Very dark
            avgLuma < 90f -> CaptureMode.SMART   // Dim/indoor
            else -> CaptureMode.FAST              // Good light
        }
    }

    /**
     * Get ISO and exposure settings for each mode.
     * @return Pair of (ISO, exposureNs)
     */
    fun getIsoExposure(mode: CaptureMode): Pair<Int, Long> {
        return when (mode) {
            CaptureMode.NIGHT -> Pair(1600, 100_000_000L)  // High ISO + long exposure
            CaptureMode.SMART -> Pair(800, 50_000_000L)    // Balanced
            CaptureMode.FAST -> Pair(200, 10_000_000L)     // Fast + clean
        }
    }

    /**
     * Estimate average luminance of a bitmap (fast sampling).
     */
    fun estimateLuma(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 128f

        val stepX = (w / 32).coerceAtLeast(1)
        val stepY = (h / 32).coerceAtLeast(1)
        var sum = 0L
        var count = 0

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // ITU-R BT.709 luma
                sum += (0.2126 * r + 0.7152 * g + 0.0722 * b).toLong()
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count > 0) (sum.toFloat() / count) else 128f
    }

    /**
     * Histogram-based auto brightness correction.
     * Analyzes image and applies dynamic gain to achieve target brightness.
     */
    fun adjustBrightness(bitmap: Bitmap, targetLuma: Float = 130f): Bitmap {
        val avgLuma = estimateLuma(bitmap)
        
        // Calculate gain to reach target brightness
        val gain = when {
            avgLuma < 50f -> 2.0f       // Very dark - aggressive boost
            avgLuma < 80f -> 1.6f       // Dark
            avgLuma < 110f -> 1.3f      // Dim
            avgLuma > 200f -> 0.75f     // Overexposed - reduce
            avgLuma > 180f -> 0.85f     // Bright
            else -> 1.0f                 // OK
        }

        if (abs(gain - 1.0f) < 0.05f) return bitmap  // Skip if minimal adjustment

        val cm = ColorMatrix().apply {
            setScale(gain, gain, gain, 1f)
        }

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, 
            bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    /**
     * Night mode enhancement with brightness boost and contrast.
     * More aggressive than regular adjustBrightness.
     */
    fun enhanceNight(bitmap: Bitmap): Bitmap {
        val avgLuma = estimateLuma(bitmap)
        
        // Night-specific matrix: boost brightness + slight contrast
        val brightBoost = when {
            avgLuma < 30f -> 35f   // Very dark
            avgLuma < 60f -> 25f   // Dark
            avgLuma < 90f -> 15f   // Dim
            else -> 5f
        }
        
        val contrast = when {
            avgLuma < 50f -> 1.4f   // Boost contrast in dark images
            avgLuma < 80f -> 1.25f
            else -> 1.15f
        }

        val t = (-0.5f * contrast + 0.5f) * 255f + brightBoost
        val matrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))

        val result = Bitmap.createBitmap(bitmap.width, bitmap.height,
            bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    /**
     * Fast box blur denoise - reduces grain noise from high ISO.
     * Uses 3x3 kernel averaging.
     */
    fun denoise(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return bitmap

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val output = IntArray(width * height)
        
        // Skip edges, process interior with 3x3 kernel
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var r = 0
                var g = 0
                var b = 0

                // 3x3 box average
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val idx = (y + dy) * width + (x + dx)
                        val p = pixels[idx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }

                r /= 9
                g /= 9
                b /= 9

                output[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // Copy edges unchanged
        for (x in 0 until width) {
            output[x] = pixels[x]                           // Top row
            output[(height - 1) * width + x] = pixels[(height - 1) * width + x]  // Bottom row
        }
        for (y in 0 until height) {
            output[y * width] = pixels[y * width]           // Left column
            output[y * width + width - 1] = pixels[y * width + width - 1]  // Right column
        }

        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Edge sharpening using 3x3 unsharp mask kernel.
     * Makes edges crisp without over-sharpening.
     */
    fun sharpen(bitmap: Bitmap, strength: Float = 1.0f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 3 || height < 3) return bitmap

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val output = IntArray(width * height)

        // Unsharp mask kernel scaled by strength
        val center = (4 * strength + 1).toInt()
        val edge = -strength.toInt().coerceAtLeast(0)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var r = 0
                var g = 0
                var b = 0

                // Apply sharpening kernel: [0,-1,0], [-1,5,-1], [0,-1,0]
                val idx = y * width + x
                val top = (y - 1) * width + x
                val bottom = (y + 1) * width + x

                // Center pixel * 5
                var p = pixels[idx]
                r += ((p shr 16) and 0xFF) * 5
                g += ((p shr 8) and 0xFF) * 5
                b += (p and 0xFF) * 5

                // Top pixel * -1
                p = pixels[top]
                r -= (p shr 16) and 0xFF
                g -= (p shr 8) and 0xFF
                b -= p and 0xFF

                // Bottom pixel * -1
                p = pixels[bottom]
                r -= (p shr 16) and 0xFF
                g -= (p shr 8) and 0xFF
                b -= p and 0xFF

                // Left pixel * -1
                p = pixels[idx - 1]
                r -= (p shr 16) and 0xFF
                g -= (p shr 8) and 0xFF
                b -= p and 0xFF

                // Right pixel * -1
                p = pixels[idx + 1]
                r -= (p shr 16) and 0xFF
                g -= (p shr 8) and 0xFF
                b -= p and 0xFF

                // Clamp
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                output[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // Copy edges unchanged
        for (x in 0 until width) {
            output[x] = pixels[x]
            output[(height - 1) * width + x] = pixels[(height - 1) * width + x]
        }
        for (y in 0 until height) {
            output[y * width] = pixels[y * width]
            output[y * width + width - 1] = pixels[y * width + width - 1]
        }

        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Enhance face region - brighten and slightly smooth.
     * @param bitmap Source image
     * @param faceRect Face bounds (from Camera2 face detection)
     */
    fun enhanceFace(bitmap: Bitmap, faceRect: Rect): Bitmap {
        // Validate face rect
        val safeRect = Rect(
            faceRect.left.coerceIn(0, bitmap.width - 1),
            faceRect.top.coerceIn(0, bitmap.height - 1),
            faceRect.right.coerceIn(1, bitmap.width),
            faceRect.bottom.coerceIn(1, bitmap.height)
        )
        
        if (safeRect.width() < 10 || safeRect.height() < 10) return bitmap

        try {
            val result = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)

            // Face enhancement matrix: slight brightness + warmth
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                    set(floatArrayOf(
                        1.15f, 0f, 0f, 0f, 12f,    // R + brightness
                        0f, 1.12f, 0f, 0f, 10f,    // G + brightness
                        0f, 0f, 1.08f, 0f, 8f,     // B slightly less (warmer)
                        0f, 0f, 0f, 1f, 0f
                    ))
                })
            }

            // Extract and enhance face region
            val faceBitmap = Bitmap.createBitmap(
                bitmap,
                safeRect.left,
                safeRect.top,
                safeRect.width(),
                safeRect.height()
            )

            canvas.drawBitmap(faceBitmap, null, safeRect, paint)
            faceBitmap.recycle()
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Face enhancement failed: ${e.message}")
            return bitmap
        }
    }

    /**
     * Merge multiple frames (for night mode noise reduction).
     * Simple averaging reduces random noise while preserving detail.
     */
    fun mergeFrames(frames: List<Bitmap>): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0]

        val width = frames[0].width
        val height = frames[0].height
        val count = frames.size

        // Accumulate pixel values
        val sumR = IntArray(width * height)
        val sumG = IntArray(width * height)
        val sumB = IntArray(width * height)

        for (frame in frames) {
            if (frame.width != width || frame.height != height) continue
            
            val pixels = IntArray(width * height)
            frame.getPixels(pixels, 0, width, 0, 0, width, height)

            for (i in pixels.indices) {
                val p = pixels[i]
                sumR[i] += (p shr 16) and 0xFF
                sumG[i] += (p shr 8) and 0xFF
                sumB[i] += p and 0xFF
            }
        }

        // Average
        val output = IntArray(width * height)
        for (i in output.indices) {
            val r = sumR[i] / count
            val g = sumG[i] / count
            val b = sumB[i] / count
            output[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(width, height, frames[0].config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)
        return result
    }

    /**
     * Apply full enhancement pipeline based on mode.
     * @param bitmap Source image
     * @param mode Capture mode (FAST/SMART/NIGHT)
     * @param faceRect Optional face bounds for face enhancement
     */
    fun enhance(bitmap: Bitmap, mode: CaptureMode, faceRect: Rect? = null): Bitmap {
        var result = bitmap

        when (mode) {
            CaptureMode.FAST -> {
                // Minimal processing - just slight color correction
                result = applyFastEnhance(bitmap)
            }
            CaptureMode.SMART -> {
                // Balanced: brightness + light sharpen
                result = adjustBrightness(bitmap)
                result = sharpen(result, 0.8f)
                applyColorBoost(result, 1.08f)  // Slight saturation
            }
            CaptureMode.NIGHT -> {
                // Full pipeline: denoise -> brightness -> sharpen
                result = denoise(bitmap)
                result = enhanceNight(result)
                result = sharpen(result, 0.6f)  // Lighter sharpen (denoise softens)
            }
        }

        // Face enhancement (if detected)
        if (faceRect != null && faceRect.width() > 20 && faceRect.height() > 20) {
            result = enhanceFace(result, faceRect)
        }

        return result
    }

    /**
     * Fast enhancement - minimal processing for good light conditions.
     */
    private fun applyFastEnhance(bitmap: Bitmap): Bitmap {
        val avgLuma = estimateLuma(bitmap)
        
        // Only adjust if clearly wrong
        if (avgLuma < 70f || avgLuma > 190f) {
            return adjustBrightness(bitmap)
        }
        
        // Light saturation boost
        return applyColorBoost(bitmap, 1.05f)
    }

    /**
     * Apply saturation/color boost.
     */
    private fun applyColorBoost(bitmap: Bitmap, saturation: Float): Bitmap {
        val cm = ColorMatrix().apply {
            setSaturation(saturation)
        }
        
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height,
            bitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    /**
     * Compress bitmap to JPEG with network-aware quality.
     * @param bitmap Source image
     * @param lowNetwork True if network is weak
     * @param qualityMode "fast" | "normal" | "hd"
     */
    fun compress(bitmap: Bitmap, lowNetwork: Boolean, qualityMode: String = "normal"): ByteArray {
        val quality = when {
            lowNetwork -> when (qualityMode) {
                "fast" -> 80
                "hd" -> 90
                else -> 86
            }
            else -> when (qualityMode) {
                "fast" -> 88
                "hd" -> 94
                else -> 92
            }
        }

        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Mirror bitmap horizontally (for front camera).
     */
    fun mirrorHorizontally(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix().apply {
            preScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
