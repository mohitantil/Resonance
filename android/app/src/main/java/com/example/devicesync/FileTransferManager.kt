package com.example.devicesync

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.example.devicesync.data.SyncDatabase
import com.example.devicesync.data.TransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object FileTransferManager {

    private const val TAG = "FileTransfer"
    const val MAX_TRANSFER_BYTES: Long = 5L * 1024L * 1024L * 1024L // 5 GB (64-bit Long)
    const val WIFI_SAFE_ENCRYPTED_THRESHOLD: Long = 500L * 1024L * 1024L // 500 MB pre-flight warning on shared LAN
    private const val MIN_PROGRESS_EMIT_DELTA_BYTES: Long = 1024L * 1024L // 1 MB
    private const val STALE_TRANSFER_TIMEOUT_MS: Long = 30L * 60L * 1000L // 30 minutes

    data class TransferInfo(
        val token: String,
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val requiresEncryption: Boolean,
        val createdAt: Long = System.currentTimeMillis()
    )

    suspend fun pruneStaleTransfers(context: Context) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - STALE_TRANSFER_TIMEOUT_MS
        val staleTokens = activeTransfers.values
            .filter { it.createdAt < cutoff }
            .map { it.token }

        for (token in staleTokens) {
            val removed = activeTransfers.remove(token)
            if (removed != null) {
                updateProgress(token, removed.fileName, 0L, removed.size, "failed")
            }
        }

        try {
            val db = SyncDatabase.getInstance(context)
            db.transferDao().failStaleTransfers(cutoff)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prune stale transfers in DB", e)
        }
    }

    data class TransferProgress(
        val token: String,
        val fileName: String,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val status: String // "in_progress", "completed", "failed"
    ) {
        val percent: Int
            get() = if (totalBytes > 0L) ((bytesTransferred * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    private val activeTransfers = ConcurrentHashMap<String, TransferInfo>()
    private val _progressFlow = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val progressFlow: StateFlow<Map<String, TransferProgress>> = _progressFlow.asStateFlow()

    sealed class PreFlightResult {
        data class Ready(val uris: List<Uri>, val totalBytes: Long, val isHotspotMode: Boolean) : PreFlightResult()
        data class WarnSwitchToHotspot(val uris: List<Uri>, val totalBytes: Long) : PreFlightResult()
        data class RejectedExceedsMax(val fileName: String, val sizeBytes: Long) : PreFlightResult()
    }

    suspend fun evaluatePreFlight(
        context: Context,
        uris: List<Uri>,
        isHotspotMode: Boolean = SyncForegroundService.areAllClientsOnHotspot()
    ): PreFlightResult = withContext(Dispatchers.IO) {
        var totalBytes = 0L
        for (uri in uris) {
            val (name, size, _) = extractMetadata(context, uri)
            if (size > MAX_TRANSFER_BYTES) {
                return@withContext PreFlightResult.RejectedExceedsMax(name, size)
            }
            totalBytes += size
        }
        if (!isHotspotMode && totalBytes > WIFI_SAFE_ENCRYPTED_THRESHOLD) {
            return@withContext PreFlightResult.WarnSwitchToHotspot(uris, totalBytes)
        }
        PreFlightResult.Ready(uris, totalBytes, isHotspotMode)
    }

    /**
     * Grants SyncForegroundService and our app UID explicit URI read permission so background streaming
     * survives even after MainActivity or ShareReceiverActivity finishes.
     */
    fun bindUrisToServiceLifetime(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        for (uri in uris) {
            try {
                context.grantUriPermission(
                    context.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
        }
        val serviceIntent = Intent(context, SyncForegroundService::class.java).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("transfer_uris", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    suspend fun registerTransfers(
        context: Context,
        uris: List<Uri>,
        isHotspotMode: Boolean = SyncForegroundService.areAllClientsOnHotspot()
    ): List<TransferInfo> = withContext(Dispatchers.IO) {
        pruneStaleTransfers(context)
        if (uris.isEmpty()) return@withContext emptyList()

        withContext(Dispatchers.Main) {
            bindUrisToServiceLifetime(context, uris)
        }

        val results = mutableListOf<TransferInfo>()
        val db = SyncDatabase.getInstance(context)
        val transferRepo = TransferRepository(db.transferDao())

        for (uri in uris) {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) { }

                val (rawFileName, size, mimeType) = extractMetadata(context, uri)
                if (size <= 0L || size > MAX_TRANSFER_BYTES) {
                    Log.w(TAG, "Skipping $rawFileName: invalid or >5GB size ($size bytes)")
                    continue
                }

                val cleanFileName = rawFileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val token = UUID.randomUUID().toString()

                val info = TransferInfo(
                    token = token,
                    uri = uri,
                    fileName = cleanFileName,
                    mimeType = mimeType,
                    size = size,
                    requiresEncryption = !isHotspotMode
                )

                activeTransfers[token] = info
                results.add(info)

                transferRepo.addTransfer(
                    fileName = cleanFileName,
                    fileSize = size,
                    mimeType = mimeType,
                    direction = "sent",
                    status = "in_progress",
                    localUriOrPath = uri.toString(),
                    transferId = token
                )

                updateProgress(token, cleanFileName, 0L, size, "in_progress")
                Log.i(TAG, "Registered zero-copy transfer: $token -> $cleanFileName ($size bytes, encrypted=${info.requiresEncryption})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register transfer for $uri", e)
            }
        }

        if (results.isNotEmpty()) {
            SyncForegroundService.notifyFilesAvailable(results)
        }

        results
    }

    fun openStream(context: Context, token: String): InputStream? {
        val info = activeTransfers[token] ?: return null
        return context.contentResolver.openInputStream(info.uri)
    }

    fun updateProgress(
        token: String,
        fileName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        status: String
    ) {
        _progressFlow.update { current ->
            if (status == "completed" || status == "failed") {
                if (!current.containsKey(token)) return@update current
                val mutable = current.toMutableMap()
                mutable.remove(token)
                return@update mutable
            }

            val existing = current[token]
            val newPct = if (totalBytes > 0L) ((bytesTransferred * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
            if (existing != null &&
                existing.status == status &&
                existing.percent == newPct &&
                bytesTransferred != 0L &&
                bytesTransferred < totalBytes &&
                kotlin.math.abs(bytesTransferred - existing.bytesTransferred) < MIN_PROGRESS_EMIT_DELTA_BYTES
            ) {
                return@update current // Skip redundant Map clone & Compose recomposition
            }

            val mutable = current.toMutableMap()
            mutable[token] = TransferProgress(token, fileName, bytesTransferred, totalBytes, status)
            mutable
        }
    }

    suspend fun markTransferFinished(context: Context, token: String, success: Boolean) {
        val info = activeTransfers.remove(token)
        val status = if (success) "completed" else "failed"
        updateProgress(token, info?.fileName ?: "", info?.size ?: 0L, info?.size ?: 0L, status)
        withContext(Dispatchers.IO) {
            val db = SyncDatabase.getInstance(context)
            TransferRepository(db.transferDao()).updateTransferStatus(
                id = token,
                status = status,
                localUriOrPath = info?.uri?.toString()
            )
        }
    }

    private fun extractMetadata(context: Context, uri: Uri): Triple<String, Long, String> {
        var fileName = "file_${System.currentTimeMillis()}"
        var size = -1L

        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        fileName = cursor.getString(nameIdx)
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
                    }
                }
            }
            if (size <= 0L) {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0L) size = afd.length
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query URI metadata for $uri", e)
        }

        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return Triple(fileName, size, mimeType)
    }

    fun getTransfer(token: String): TransferInfo? = activeTransfers[token]
    fun listTransfers(): List<TransferInfo> = activeTransfers.values.toList()
}
