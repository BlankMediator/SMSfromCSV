package com.blankmediator.smsfromcsv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.telephony.SmsManager
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object ImagePreparer {
    private const val FALLBACK_MAX_MESSAGE_BYTES = 300 * 1024
    private const val FALLBACK_MAX_WIDTH = 1280
    private const val FALLBACK_MAX_HEIGHT = 1280
    private const val MIN_JPEG_QUALITY = 40
    private const val PDU_OVERHEAD_RESERVE = 12 * 1024

    data class Limits(
        val maxMessageBytes: Int,
        val maxWidth: Int,
        val maxHeight: Int,
        val enabled: Boolean
    )

    data class Inspection(
        val width: Int,
        val height: Int,
        val sourceBytes: Long
    )

    data class Prepared(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val quality: Int,
        val sourceBytes: Long,
        val maxMessageBytes: Int
    )

    fun limits(smsManager: SmsManager): Limits {
        val config = runCatching { smsManager.carrierConfigValues }.getOrNull()
        val enabled = config?.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED, true) ?: true
        return Limits(
            maxMessageBytes = config?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, FALLBACK_MAX_MESSAGE_BYTES)
                ?.takeIf { it > 0 } ?: FALLBACK_MAX_MESSAGE_BYTES,
            maxWidth = config?.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH, FALLBACK_MAX_WIDTH)
                ?.takeIf { it > 0 } ?: FALLBACK_MAX_WIDTH,
            maxHeight = config?.getInt(SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT, FALLBACK_MAX_HEIGHT)
                ?.takeIf { it > 0 } ?: FALLBACK_MAX_HEIGHT,
            enabled = enabled
        )
    }

    fun inspect(file: File): Inspection {
        require(file.isFile) { "Image file is missing: ${file.name}" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) {
            "Image '${file.name}' could not be decoded. Use a normal JPEG, PNG or WebP image."
        }
        return Inspection(options.outWidth, options.outHeight, file.length())
    }

    fun prepare(file: File, text: String, limits: Limits): Prepared {
        require(limits.enabled) { "MMS is disabled by this carrier/SIM." }
        val inspection = inspect(file)
        val textBytes = text.toByteArray(Charsets.UTF_8).size
        val imageBudget = limits.maxMessageBytes - textBytes - PDU_OVERHEAD_RESERVE
        require(imageBudget >= 24 * 1024) {
            "Message text leaves too little room for an image within this carrier's MMS limit (${limits.maxMessageBytes / 1024} KB)."
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, limits.maxWidth, limits.maxHeight)
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: error("Image '${file.name}' could not be decoded.")

        try {
            var working = scaleToFit(decoded, limits.maxWidth, limits.maxHeight)
            if (working !== decoded) decoded.recycle()

            try {
                var quality = 90
                repeat(8) {
                    val encoded = encodeJpeg(working, quality)
                    if (encoded.size <= imageBudget) {
                        return Prepared(
                            jpegBytes = encoded,
                            width = working.width,
                            height = working.height,
                            quality = quality,
                            sourceBytes = inspection.sourceBytes,
                            maxMessageBytes = limits.maxMessageBytes
                        )
                    }

                    if (quality > MIN_JPEG_QUALITY) {
                        quality = (quality - 10).coerceAtLeast(MIN_JPEG_QUALITY)
                    } else {
                        val nextWidth = (working.width * 0.82).roundToInt().coerceAtLeast(160)
                        val nextHeight = (working.height * 0.82).roundToInt().coerceAtLeast(120)
                        if (nextWidth >= working.width && nextHeight >= working.height) return@repeat
                        val scaled = Bitmap.createScaledBitmap(working, nextWidth, nextHeight, true)
                        if (scaled !== working) working.recycle()
                        working = scaled
                        quality = 75
                    }
                }

                val last = encodeJpeg(working, MIN_JPEG_QUALITY)
                require(last.size <= imageBudget) {
                    "Image '${file.name}' cannot be reduced enough for this carrier's MMS limit (${limits.maxMessageBytes / 1024} KB)."
                }
                return Prepared(
                    jpegBytes = last,
                    width = working.width,
                    height = working.height,
                    quality = MIN_JPEG_QUALITY,
                    sourceBytes = inspection.sourceBytes,
                    maxMessageBytes = limits.maxMessageBytes
                )
            } finally {
                if (!working.isRecycled) working.recycle()
            }
        } catch (t: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw t
        }
    }

    internal fun calculateInSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sample = 1
        // Sample very wide or very tall images before decoding as well. Using && here can
        // decode a huge panoramic bitmap at full resolution and exhaust the app's memory.
        while (width / (sample * 2) >= maxWidth || height / (sample * 2) >= maxHeight) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) return bitmap
        val ratio = max(
            bitmap.width.toDouble() / maxWidth.toDouble(),
            bitmap.height.toDouble() / maxHeight.toDouble()
        )
        val width = (bitmap.width / ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height / ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        return ByteArrayOutputStream().use { out ->
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                "Could not encode JPEG for MMS."
            }
            out.toByteArray()
        }
    }
}
