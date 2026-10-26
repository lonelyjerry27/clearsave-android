package app.clearsave

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/** Where a file ended up, and a short explanation if it couldn't go where the user asked. */
data class SaveResult(val location: String, val note: String? = null)

/**
 * Copies a finished file to its final home.
 *
 * If the user picked a folder with "Change…", files go there (through Android's folder-access permission).
 * Otherwise: music to Music/ClearSave and videos to Movies/ClearSave (where music players and galleries look),
 * and everything else (ZIP files) to Downloads/ClearSave.
 */
object Saver {
    private const val FOLDER = "ClearSave"
    private const val PREFS = "settings"
    private const val KEY_TREE = "saveTreeUri"
    const val DEFAULT_LABEL = "Music and Movies folders"
    private val audioExt = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav")
    private val videoExt = setOf("mp4", "mkv", "webm")

    // ------------------------------------------------------------------ the folder the user chose
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen folder, if it is set and Android still lets this app write to it. */
    fun customTree(ctx: Context): Uri? {
        val saved = prefs(ctx).getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(saved)
        val allowed = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return if (allowed) uri else null
    }

    /** Remember a new folder (or null to go back to the default), releasing the permission for the old one. */
    fun setCustomTree(ctx: Context, uri: Uri?) {
        prefs(ctx).getString(KEY_TREE, null)?.let { old ->
            val oldUri = Uri.parse(old)
            if (oldUri != uri) {
                try {
                    ctx.contentResolver.releasePersistableUriPermission(
                        oldUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (_: Exception) {
                }
            }
        }
        prefs(ctx).edit().apply { if (uri == null) remove(KEY_TREE) else putString(KEY_TREE, uri.toString()) }.apply()
    }

    /** Human-readable name of where files go, for the "Saving to …" line. */
    fun label(ctx: Context): String = customTree(ctx)?.let { treeLabel(it) } ?: DEFAULT_LABEL

    private fun treeLabel(tree: Uri): String {
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return "your chosen folder" }
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        return when {
            volume == "primary" -> path.ifEmpty { "Internal storage" }
            path.isEmpty() -> "SD card"
            else -> "SD card/$path"
        }
    }

    // ------------------------------------------------------------------ saving
    fun save(ctx: Context, file: File, name: String): SaveResult {
        var note: String? = null
        val tree = customTree(ctx)
        if (tree != null) {
            try {
                return SaveResult(saveToTree(ctx, tree, file, name))
            } catch (e: Exception) {
                note = "Couldn't save to ${treeLabel(tree)}, so this went to the default folder."
            }
        } else if (prefs(ctx).contains(KEY_TREE)) {   // chosen once, but Android no longer allows access
            note = "Your chosen folder isn't available any more, so this went to the default folder."
            setCustomTree(ctx, null)
        }
        return SaveResult(saveToDefault(ctx, file, name), note)
    }

    private fun saveToTree(ctx: Context, tree: Uri, file: File, name: String): String {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IOException("folder unavailable")
        if (!root.exists() || !root.canWrite()) throw IOException("folder not writable")
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var finalName = name
        var n = 1
        while (root.findFile(finalName) != null) {   // never overwrite an earlier download
            finalName = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            n++
        }
        val doc = root.createFile(safeMime(ext.lowercase()), finalName) ?: throw IOException("couldn't create the file")
        try {
            ctx.contentResolver.openOutputStream(doc.uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        return treeLabel(tree)
    }

    /** For a user-chosen folder: only well-known types get a specific type, so the file name is never altered. */
    private fun safeMime(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "flac" -> "audio/flac"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun saveToDefault(ctx: Context, file: File, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val (collection: Uri, baseDir: String) = when (ext) {
            in audioExt -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            in videoExt -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val mime = mimeFor(ext)

        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Couldn't create the file in $baseDir.")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(baseDir), FOLDER)
            if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Couldn't create the $baseDir/$FOLDER folder.")
            val dest = uniqueFile(dir, name)
            file.copyTo(dest)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime), null)
        }
        return "$baseDir/$FOLDER"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var n = 1
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return f
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "opus", "ogg" -> "audio/ogg"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
