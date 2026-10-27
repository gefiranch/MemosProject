package com.example.memosproject.data.goals

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

const val MAX_GOAL_IMAGE_BYTES = 5 * 1024 * 1024

/** The picker grants access to just the selected file; no media permission is needed. */
class GoalImageFiles(private val context: Context) {
    suspend fun read(uri: String): GoalImageUpload = withContext(Dispatchers.IO) {
        val source = uri.toUri()
        val type = context.contentResolver.getType(source)?.lowercase()
        val extension = when (type) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> throw IllegalArgumentException("Pilih gambar JPG, PNG, atau WebP.")
        }
        val bytes = context.contentResolver.openInputStream(source)?.use { stream ->
            // Read at most limit + 1, even if the provider doesn't report the file size.
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (result.size() <= MAX_GOAL_IMAGE_BYTES) {
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_GOAL_IMAGE_BYTES + 1 - result.size()))
                if (count < 0) break
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } ?: throw IllegalArgumentException("Gambar tidak dapat dibuka. Silakan pilih ulang.")
        require(bytes.isNotEmpty() && bytes.size <= MAX_GOAL_IMAGE_BYTES) { "Ukuran gambar maksimal 5 MB." }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "File gambar tidak valid." }
        GoalImageUpload(bytes, requireNotNull(type), extension)
    }
}

suspend fun decodeGoalImage(bytes: ByteArray): Bitmap = withContext(Dispatchers.Default) {
    require(bytes.size <= MAX_GOAL_IMAGE_BYTES) { "Ukuran gambar terlalu besar." }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val scale = maxOf(info.size.width, info.size.height).toFloat() / 1200f
            if (scale > 1f) decoder.setTargetSize(
                (info.size.width / scale).toInt().coerceAtLeast(1),
                (info.size.height / scale).toInt().coerceAtLeast(1)
            )
        }
    } else {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1200) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "Gambar tidak dapat dibaca." }
    }
}
