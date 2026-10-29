package com.example.devicesync.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/**
 * Manages destination storage locations for files received on Android.
 * Supports:
 *  1. Default: Zero-permission automatic categorization into MediaStore
 *     (Pictures/SyncDevice, Movies/SyncDevice, Music/SyncDevice, Download/SyncDevice)
 *  2. Custom: User-selected folder via Storage Access Framework (SAF)
 */
object StoragePreferences {

    private const val TAG = "StoragePrefs"
    private const val PREFS_NAME = "devicesync_storage"
    private const val KEY_CUSTOM_URI = "custom_storage_tree_uri"
    private const val KEY_USE_CUSTOM = "use_custom_storage"

    data class TargetDestination(
        val uri: Uri,
        val outputStream: OutputStream,
        val isMediaStore: Boolean = false
    )

    fun isCustomStorageEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_CUSTOM_URI, null)
        return prefs.getBoolean(KEY_USE_CUSTOM, false) && !uriString.isNullOrBlank()
    }

    fun getCustomStorageUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_CUSTOM_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    fun setCustomStorageUri(context: Context, treeUri: Uri?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not take persistable URI permission", e)
            }
            prefs.edit()
                .putString(KEY_CUSTOM_URI, treeUri.toString())
                .putBoolean(KEY_USE_CUSTOM, true)
                .apply()
        } else {
            prefs.edit()
                .remove(KEY_CUSTOM_URI)
                .putBoolean(KEY_USE_CUSTOM, false)
                .apply()
        }
    }

    /**
     * Get a user-friendly description of the current storage path.
     */
    fun getStoragePathDescription(context: Context): String {
        if (isCustomStorageEnabled(context)) {
            val treeUri = getCustomStorageUri(context)
            if (treeUri != null) {
                val doc = DocumentFile.fromTreeUri(context, treeUri)
                val name = doc?.name
                if (!name.isNullOrBlank()) return "Custom: $name"
            }
        }
        return "Default (Pictures, Movies, Download / SyncDevice)"
    }

    /**
     * Creates the destination output stream for an incoming file.
     */
    fun openDestinationStream(
        context: Context,
        fileName: String,
        mimeType: String
    ): TargetDestination? {
        // Option 1: Custom SAF folder if configured
        if (isCustomStorageEnabled(context)) {
            val treeUri = getCustomStorageUri(context)
            if (treeUri != null) {
                try {
                    val rootDir = DocumentFile.fromTreeUri(context, treeUri)
                    if (rootDir != null && rootDir.canWrite()) {
                        val fileDoc = rootDir.createFile(mimeType, fileName)
                        if (fileDoc != null) {
                            val stream = context.contentResolver.openOutputStream(fileDoc.uri)
                            if (stream != null) {
                                return TargetDestination(
                                    uri = fileDoc.uri,
                                    outputStream = stream,
                                    isMediaStore = false
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed writing to custom SAF folder, falling back to default", e)
                }
            }
        }

        // Option 2: Default MediaStore insertion
        return createMediaStoreDestination(context, fileName, mimeType)
    }

    /**
     * Creates an entry in MediaStore and opens an output stream.
     */
    private fun createMediaStoreDestination(
        context: Context,
        fileName: String,
        mimeType: String
    ): TargetDestination? {
        val resolver = context.contentResolver

        val (collectionUri, relativePath) = when {
            mimeType.startsWith("image/") -> {
                Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/SyncDevice")
            }
            mimeType.startsWith("video/") -> {
                Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/SyncDevice")
            }
            mimeType.startsWith("audio/") -> {
                Pair(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Music/SyncDevice")
            }
            else -> {
                Pair(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/SyncDevice")
            }
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collectionUri, contentValues) ?: return null
        val stream = resolver.openOutputStream(uri) ?: return null

        return TargetDestination(
            uri = uri,
            outputStream = stream,
            isMediaStore = true
        )
    }

    /**
     * Marks the file as finished (clears IS_PENDING so other apps can read it immediately).
     */
    fun finalizeDestination(context: Context, destination: TargetDestination) {
        if (destination.isMediaStore && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(destination.uri, contentValues, null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear IS_PENDING on ${destination.uri}", e)
            }
        }
    }

    /**
     * Deletes an incomplete destination URI if an incoming upload fails or is cancelled mid-stream.
     */
    fun deleteDestination(context: Context, destination: TargetDestination) {
        try {
            if (destination.isMediaStore) {
                context.contentResolver.delete(destination.uri, null, null)
            } else {
                DocumentFile.fromSingleUri(context, destination.uri)?.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up partial destination ${destination.uri}", e)
        }
    }
}
