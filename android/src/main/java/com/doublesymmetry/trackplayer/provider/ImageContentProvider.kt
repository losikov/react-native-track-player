package com.doublesymmetry.trackplayer.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException

/**
 * ContentProvider for exposing cached images to Android Auto.
 * Converts file:// URIs to content:// URIs that Android Auto can access.
 * 
 * This follows the Android documentation pattern:
 * https://developer.android.com/training/cars/media/create-media-browser/media-artwork
 */
class ImageContentProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        // Determine MIME type based on file extension
        val path = uri.path ?: return null
        return when {
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".webp", ignoreCase = true) -> "image/webp"
            path.endsWith(".gif", ignoreCase = true) -> "image/gif"
            else -> "image/*"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        return 0
    }

    /**
     * Opens a file and returns a ParcelFileDescriptor for reading.
     * The URI path should point to a cached image file.
     * 
     * URI format: content://{authority}/images/{bookId}/{filename}
     * Example: content://life.soberhub.app.trackplayer.imageprovider/images/book123/thumbnail.jpg
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: run {
            Timber.e("Context is null")
            return null
        }
        
        try {
            // Extract the file path from the URI
            // URI format: content://{authority}/images/{bookId}/{filename}
            val path = uri.path ?: run {
                Timber.e("URI path is null: $uri")
                throw FileNotFoundException("URI path is null: $uri")
            }
            
            // Remove leading slash if present
            var cleanPath = if (path.startsWith("/")) path.substring(1) else path
            
            // Security: Prevent directory traversal attacks
            // Normalize path to remove ".." and "." components
            if (cleanPath.contains("..") || cleanPath.contains("//")) {
                Timber.w("Rejected suspicious path with directory traversal: $cleanPath")
                throw FileNotFoundException("Invalid path: $cleanPath")
            }
            
            // Try to find the file in common cache locations
            val file = findCachedImageFile(context, cleanPath)
            
            if (file == null) {
                Timber.w("Image file not found: $cleanPath")
                throw FileNotFoundException("Image file not found: $cleanPath")
            }
            
            if (!file.exists()) {
                Timber.w("Image file does not exist: ${file.absolutePath}")
                throw FileNotFoundException("Image file does not exist: ${file.absolutePath}")
            }
            
            if (!file.canRead()) {
                Timber.w("Image file is not readable: ${file.absolutePath}")
                throw FileNotFoundException("Image file is not readable: ${file.absolutePath}")
            }
            
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: FileNotFoundException) {
            Timber.e(e, "FileNotFoundException opening file for URI: $uri")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error opening file for URI: $uri")
            throw FileNotFoundException("Error opening file: ${e.message}")
        }
    }

    /**
     * Finds the cached image file in common storage locations.
     * Checks both external files directory and internal files directory.
     */
    private fun findCachedImageFile(context: Context, path: String): File? {
        // Try external files directory first (most common for React Native)
        val externalFilesDir = context.getExternalFilesDir(null)
        if (externalFilesDir != null) {
            val externalFile = File(externalFilesDir, path)
            if (externalFile.exists()) {
                return externalFile
            }
        }
        
        // Try internal files directory
        val internalFile = File(context.filesDir, path)
        if (internalFile.exists()) {
            return internalFile
        }
        
        // Try cache directory as fallback
        val cacheFile = File(context.cacheDir, path)
        if (cacheFile.exists()) {
            return cacheFile
        }
        
        return null
    }
}

