package com.nicolous.statusgallery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Copies a status into the phone's gallery: Pictures, Movies or Music / StatusGallery. */
object Downloader {
    const val DIR_IMAGES = "Pictures/StatusGallery"
    const val DIR_VIDEOS = "Movies/StatusGallery"
    const val DIR_AUDIO = "Music/StatusGallery"

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "opus" -> "audio/opus"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "amr" -> "audio/amr"
        "wav" -> "audio/x-wav"
        else -> "application/octet-stream"
    }

    private fun stillThere(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    fun delete(ctx: Context, m: Media): Boolean {
        val n = try {
            ctx.contentResolver.delete(m.uri, null, null)
        } catch (e: Exception) {
            0
        }
        Store.forgetUri(ctx, m.key)
        return n > 0
    }

    /** Returns the uri of the downloaded copy (an existing one if it was downloaded before), or null on failure. */
    fun save(ctx: Context, m: Media): Uri? {
        if (m.saved) return m.uri
        Store.savedUri(ctx, m.name)?.let { old ->
            val u = Uri.parse(old)
            if (stillThere(ctx, u)) return u
        }
        val cr = ctx.contentResolver
        val (collection, dir) = when (m.kind) {
            Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to DIR_IMAGES
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to DIR_VIDEOS
            Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to DIR_AUDIO
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "status_" + m.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime(m.name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val out = cr.insert(collection, values) ?: return null
        return try {
            val input = cr.openInputStream(m.uri) ?: throw IllegalStateException("no input")
            input.use { i -> cr.openOutputStream(out)!!.use { o -> i.copyTo(o) } }
            cr.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Store.markSaved(ctx, m.name, out.toString())
            out
        } catch (e: Exception) {
            cr.delete(out, null, null)
            null
        }
    }
}
