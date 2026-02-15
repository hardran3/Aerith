package com.aerith.core.data

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import coil.Coil
import com.aerith.core.blossom.BlossomBlob
import java.io.File

/**
 * Manages persistent local storage for blobs in the public Pictures folder.
 * Uses MediaStore API for reliable discovery across app reinstalls.
 */
class BlobVaultManager(private val context: Context) {

    private val vaultPath = "Pictures/Aerith/Vault"
    private val vaultDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Aerith/Vault"
    ).apply {
        if (!exists()) mkdirs()
    }

    /**
     * Returns a set of all SHA-256 hashes currently present in the vault.
     * Uses MediaStore to find files created by previous installs.
     */
    fun getVaultedHashes(): Set<String> {
        val hashes = mutableSetOf<String>()
        
        // 1. Try MediaStore (Reliable for reinstalls)
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$vaultPath%")
        
        val contentUris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )

        contentUris.forEach { uri ->
            try {
                context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameColumn)
                        val hash = name.substringBefore(".")
                        if (hash.length == 64) hashes.add(hash)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BlobVault", "MediaStore query failed for $uri", e)
            }
        }

        // 2. Fallback to direct File API (Reliable for current install)
        vaultDir.listFiles()?.forEach { file ->
            val hash = file.name.substringBefore(".")
            if (hash.length == 64) hashes.add(hash)
        }

        return hashes
    }

    /**
     * Returns statistics about the vault (file count and total size in bytes).
     */
    fun getVaultStats(): Pair<Int, Long> {
        val hashes = getVaultedHashes()
        var totalSize = 0L
        var count = 0

        // We have to iterate files to get sizes since MediaStore size might be inaccurate/cached
        vaultDir.listFiles()?.forEach { file ->
            val hash = file.name.substringBefore(".")
            if (hash in hashes) {
                totalSize += file.length()
                count++
            }
        }
        
        return Pair(count, totalSize)
    }

    /**
     * Gets the file handle for a blob in the vault.
     */
    fun getFile(hash: String, extension: String): File {
        val cleanExt = extension.removePrefix(".")
        val fileName = if (cleanExt.isNotEmpty()) "$hash.$cleanExt" else hash
        return File(vaultDir, fileName)
    }

    /**
     * Checks if a blob exists in the vault.
     * Optimization: Use a provided set for batch operations to avoid listFiles()
     */
    fun contains(hash: String, vaultedSet: Set<String>? = null): Boolean {
        if (vaultedSet != null) return vaultedSet.contains(hash)
        return getVaultFile(hash) != null
    }

    /**
     * Returns the file if it exists in the vault.
     */
    fun getVaultFile(hash: String): File? {
        // Fast check: common extensions first to avoid directory listing if possible
        val extensions = listOf("jpg", "png", "gif", "webp", "mp4", "mov", "webm")
        for (ext in extensions) {
            val file = File(vaultDir, "$hash.$ext")
            if (file.exists()) return file
        }
        // Fallback to prefix search for unknown extensions
        return vaultDir.listFiles { _, name -> name.startsWith(hash) }?.firstOrNull()
    }

    /**
     * Copies a file from temporary storage/cache to the vault.
     */
    fun saveToVault(hash: String, extension: String, sourceFile: File) {
        val target = getFile(hash, extension)
        if (target.exists()) return
        
        try {
            sourceFile.copyTo(target, overwrite = true)
            android.util.Log.d("BlobVault", "Saved $hash to vault: ${target.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("BlobVault", "Failed to save to vault", e)
        }
    }

    /**
     * Attempts to "vault" a file by copying it from Coil's cache if available.
     */
    fun vaultFromCache(hash: String, extension: String) {
        if (contains(hash)) return

        val imageLoader = Coil.imageLoader(context)
        val snapshot = imageLoader.diskCache?.get(hash)
        val sourceFile = snapshot?.data?.toFile()

        if (sourceFile != null && sourceFile.exists()) {
            saveToVault(hash, extension, sourceFile)
            snapshot.close()
        }
    }

    /**
     * Removes a file from the vault.
     */
    fun deleteFromVault(hash: String) {
        getVaultFile(hash)?.delete()
    }

    fun clearAll() {
        vaultDir.listFiles()?.forEach { it.delete() }
    }
}
