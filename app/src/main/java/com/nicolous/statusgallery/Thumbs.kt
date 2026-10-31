package com.nicolous.statusgallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache

/** Small preview pictures and durations, cached in memory. */
object Thumbs {
    class Info(val bmp: Bitmap?, val durationMs: Long)

    private val cache = object : LruCache<String, Info>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Info): Int = (value.bmp?.byteCount ?: 0) + 64
    }

    fun get(ctx: Context, m: Media, maxPx: Int = 360): Info {
        cache.get(m.key)?.let { return it }
        val info = try {
            when (m.kind) {
                Kind.IMAGE -> Info(decode(ctx, m, maxPx), 0)
                Kind.VIDEO -> retrieve(ctx, m, maxPx, frame = true)
                Kind.AUDIO -> retrieve(ctx, m, maxPx, frame = false)
            }
        } catch (e: Exception) {
            Info(null, 0)
        }
        cache.put(m.key, info)
        return info
    }

    /** Big version for the full screen viewer (not cached). */
    fun full(ctx: Context, m: Media, maxPx: Int = 1600): Bitmap? = try {
        decode(ctx, m, maxPx)
    } catch (e: Exception) {
        null
    }

    private fun decode(ctx: Context, m: Media, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(m.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(m.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun retrieve(ctx: Context, m: Media, maxPx: Int, frame: Boolean): Info {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, m.uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var bmp: Bitmap? = null
            if (frame) {
                val f = r.getFrameAtTime(0)
                if (f != null) {
                    val scale = maxPx.toFloat() / maxOf(f.width, f.height)
                    bmp = if (scale < 1f) {
                        Bitmap.createScaledBitmap(f, (f.width * scale).toInt(), (f.height * scale).toInt(), true)
                    } else f
                }
            }
            return Info(bmp, dur)
        } finally {
            r.release()
        }
    }
}
