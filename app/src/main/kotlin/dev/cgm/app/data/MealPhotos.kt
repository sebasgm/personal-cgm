package dev.cgm.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Photographs of meals, on disk rather than in the database.
 *
 * The row keeps a file name; the bytes live here. A BLOB column would put hundreds
 * of megabytes of JPEG inside the same database that holds two years of readings,
 * and every query, backup and migration would carry them.
 *
 * Internal storage, so the files are private to the app and go when it is
 * uninstalled. The pictures are a memory aid for the person who took them; nothing
 * in this app reads them and nothing estimates carbohydrates from them.
 */
object MealPhotos {

    private const val DIRECTORY = "meals"

    /**
     * Longest edge, in pixels, after downscaling.
     *
     * Enough to recognise a plate and read a label on it, and far below what a
     * phone camera produces. Saving the original would be ten times the size for a
     * detail nobody will look for: three meals a day at full resolution is gigabytes
     * inside two years.
     */
    private const val MAX_EDGE_PX = 1024

    /** Lossy on purpose. This is a photo of lunch, not evidence. */
    private const val JPEG_QUALITY = 80

    /**
     * How long a picture is kept.
     *
     * Ninety days, against two years for the readings, and the asymmetry is
     * deliberate: a reading from last year still contributes to a pattern, while a
     * photo of last year's lunch is bytes nobody will open. The meal itself is kept
     * either way — [CarbDao.clearPhotosBefore] forgets the picture, not the entry.
     */
    const val RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000

    /**
     * Where the photos live, created if needed.
     *
     * Everything else here takes that directory as a parameter rather than a
     * Context, so the data layer can delete and sweep files without holding on to
     * one. Only reading a picked image needs Android, and only for its resolver.
     */
    fun directoryIn(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    fun file(directory: File, name: String): File = File(directory, name)

    /**
     * Copy the image at [uri] into internal storage, downscaled.
     *
     * Returns the file name to store on the meal, or null if the image could not be
     * read — a picked file can be a broken download or something that is not an
     * image at all, and that must not cost the user the meal they were logging.
     */
    fun save(resolver: ContentResolver, uri: Uri, directory: File): String? = runCatching {
        val bitmap = decodeScaled(resolver, uri) ?: return null
        val name = "${UUID.randomUUID()}.jpg"
        directory.mkdirs()
        file(directory, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
        bitmap.recycle()
        name
    }.getOrNull()

    /** Removes a photo's file. Missing is success: the goal is for it to be gone. */
    fun delete(directory: File, name: String?) {
        name ?: return
        runCatching { file(directory, name).delete() }
    }

    /**
     * Load a photo for display, no larger than [maxEdgePx].
     *
     * The thumbnail on a diary row needs a fraction of the stored pixels, and
     * decoding the full image for each row is how a list of meals runs out of memory.
     */
    fun load(directory: File, name: String, maxEdgePx: Int): Bitmap? = runCatching {
        val path = file(directory, name).takeIf { it.exists() }?.absolutePath ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdgePx)
            },
        )
    }.getOrNull()

    /**
     * Delete files no row points at any more.
     *
     * Deleting a meal removes its file directly, so this is the safety net for the
     * cases that bypass it — a crash between the two writes, or a restore. It is
     * driven from the names still referenced rather than from timestamps, because a
     * file's age says nothing about whether a row still needs it.
     */
    fun deleteOrphans(directory: File, referenced: Set<String>): Int {
        val files = directory.listFiles() ?: return 0
        return files.count { it.name !in referenced && runCatching { it.delete() }.getOrDefault(false) }
    }

    private fun decodeScaled(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decoded at a power-of-two reduction first, which is what the decoder can
        // do cheaply, then scaled the rest of the way only if it is still too big.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val longest = max(decoded.width, decoded.height)
        if (longest <= MAX_EDGE_PX) return decoded
        val ratio = MAX_EDGE_PX.toDouble() / longest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    }

    private fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxEdgePx) sample *= 2
        return sample
    }
}
