package com.aerith.core.data

import android.content.Context
import coil.Coil
import java.io.File

/**
 * Manages permanent local storage for orphaned (Trash) media to prevent cache eviction.
 */
class MediaCacheManager(private val context: Context) {

    private val persistentDir = File(context.filesDir, "persistent_media").apply {
        if (!exists()) mkdirs()
    }

    /**
     * Attempts to "pin" a file by copying it from the LRU cache to permanent storage.
     */
    fun pinToPersistentStorage(hash: String) {
        val targetFile = File(persistentDir, hash)
        if (targetFile.exists()) return

        val imageLoader = Coil.imageLoader(context)
        val snapshot = imageLoader.diskCache?.get(hash)
        val sourceFile = snapshot?.data?.toFile()

        if (sourceFile != null && sourceFile.exists()) {
            try {
                sourceFile.copyTo(targetFile, overwrite = true)
                android.util.Log.d("MediaCacheManager", "Pinned $hash to persistent storage")
            } catch (e: Exception) {
                android.util.Log.e("MediaCacheManager", "Failed to pin $hash", e)
            } finally {
                snapshot.close()
            }
        }
    }

    /**
     * Checks if a file is pinned and returns it.
     */
    fun getPinnedFile(hash: String): File? {
        val file = File(persistentDir, hash)
        return if (file.exists()) file else null
    }

    /**
     * Removes a file from persistent storage.
     */
    fun unpinFile(hash: String) {
        val file = File(persistentDir, hash)
        if (file.exists()) {
            file.delete()
            android.util.Log.d("MediaCacheManager", "Unpinned $hash from persistent storage")
        }
    }

    fun clearAll() {
        persistentDir.listFiles()?.forEach { it.delete() }
    }
}
