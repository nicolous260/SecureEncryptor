package com.nicolous.statusgallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore

enum class Kind { IMAGE, VIDEO, AUDIO }

/** One status file (saved = a copy that already lives in our gallery folder). */
data class Media(
    val uri: Uri,
    val name: String,
    val kind: Kind,
    val modified: Long,
    val saved: Boolean
) {
    val key: String get() = uri.toString()
}

data class Data(
    val status: List<Media>,
    val saved: List<Media>,
    val savedNames: Set<String>,
    val hasFolder: Boolean
)

object Scanner {
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val VIDEOS = setOf("mp4", "3gp", "mkv", "webm", "mov")
    private val AUDIO = setOf("opus", "m4a", "mp3", "aac", "ogg", "amr", "wav")
    val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun kindOf(name: String): Kind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in IMAGES -> Kind.IMAGE
            ext in VIDEOS -> Kind.VIDEO
            ext in AUDIO -> Kind.AUDIO
            else -> null
        }
    }

    fun loadAll(ctx: Context): Data {
        val status = ArrayList<Media>()
        var hasFolder = false
        for (pkg in PACKAGES) {
            val tree = Store.tree(ctx, pkg) ?: continue
            hasFolder = true
            try {
                status += listTree(ctx, Uri.parse(tree))
            } catch (e: Exception) {
                // folder permission lost or folder missing: just show nothing for it
            }
        }
        val saved = savedItems(ctx)
        val savedUris = saved.map { it.key }.toHashSet()
        val names = Store.savedEntries(ctx).filter { it.value in savedUris }.keys.toSet()
        return Data(status.sortedByDescending { it.modified }, saved, names, hasFolder)
    }

    /** One query for the whole folder (much faster than DocumentFile). */
    private fun listTree(ctx: Context, tree: Uri): List<Media> {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<Media>()
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE
        )
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mod = c.getLong(2)
                if (c.getString(3) == Document.MIME_TYPE_DIR) continue
                val kind = kindOf(name) ?: continue
                out.add(Media(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, kind, mod, false))
            }
        }
        return out
    }

    private fun savedItems(ctx: Context): List<Media> {
        val sets = listOf(
            Triple(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Kind.IMAGE, Downloader.DIR_IMAGES),
            Triple(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Kind.VIDEO, Downloader.DIR_VIDEOS),
            Triple(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Kind.AUDIO, Downloader.DIR_AUDIO)
        )
        val out = ArrayList<Media>()
        for ((collection, kind, dir) in sets) {
            try {
                val proj = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
                ctx.contentResolver.query(
                    collection, proj,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$dir/%"),
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                        out.add(Media(uri, c.getString(1) ?: "status", kind, c.getLong(2) * 1000, true))
                    }
                }
            } catch (e: Exception) {
                // ignore this collection
            }
        }
        return out.sortedByDescending { it.modified }
    }
}
