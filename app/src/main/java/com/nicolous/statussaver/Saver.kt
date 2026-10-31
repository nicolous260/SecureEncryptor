package com.nicolous.statussaver

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

/** Lists the status files WhatsApp cached and copies one into the gallery. */
object Saver {
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val VIDEOS = setOf("mp4", "3gp", "mkv", "webm", "mov")

    class Item(val doc: DocumentFile, val name: String, val video: Boolean)

    /** Newest first. null = no folder chosen yet. */
    fun list(ctx: Context, pkg: String): List<Item>? {
        val uri = Store.tree(ctx, pkg) ?: return null
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uri)) ?: return null
        return tree.listFiles().mapNotNull { f ->
            val n = f.name ?: return@mapNotNull null
            if (!f.isFile) return@mapNotNull null
            val ext = n.substringAfterLast('.', "").lowercase()
            when {
                ext in IMAGES -> Item(f, n, false)
                ext in VIDEOS -> Item(f, n, true)
                else -> null
            }
        }.sortedByDescending { it.doc.lastModified() }
    }

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        else -> "video/quicktime"
    }

    /** Copies the status into Pictures/StatusSaver or Movies/StatusSaver. Returns the new Uri or null. */
    fun save(ctx: Context, item: Item): Uri? {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "status_" + item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(item.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (item.video) "Movies/StatusSaver" else "Pictures/StatusSaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (item.video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val out = cr.insert(collection, values) ?: return null
        return try {
            val input = cr.openInputStream(item.doc.uri) ?: throw IllegalStateException("no input")
            input.use { i -> cr.openOutputStream(out)!!.use { o -> i.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            cr.update(out, done, null, null)
            out
        } catch (e: Exception) {
            cr.delete(out, null, null)
            null
        }
    }

    /** Small preview picture (frame for videos). */
    fun thumb(ctx: Context, item: Item): Bitmap? = try {
        if (item.video) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, item.doc.uri)
                r.getFrameAtTime(0)
            } finally {
                r.release()
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(item.doc.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 900) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(item.doc.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        null
    }
}
