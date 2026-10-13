package com.example.devicesync.ui.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.devicesync.ClipboardPayload
import com.example.devicesync.FileTransferManager
import com.example.devicesync.FormatUtils
import com.example.devicesync.SyncForegroundService
import com.example.devicesync.data.*
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the main sync screen.
 * Owns all clipboard sync and file transfer state/logic.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    // ── Dependencies ────────────────────────────────────────────────────
    private val clipboardRepo: ClipboardRepository
    private val transferRepo: TransferRepository
    private val trustedDeviceDao: TrustedDeviceDao
    private val gson = Gson()

    init {
        val db = SyncDatabase.getInstance(application)
        clipboardRepo = ClipboardRepository(db.clipboardDao())
        transferRepo = TransferRepository(db.transferDao())
        trustedDeviceDao = db.trustedDeviceDao()
    }

    // ── Exposed state ───────────────────────────────────────────────────

    /** Live clipboard history from Room (newest first) */
    val clipboardItems: StateFlow<List<ClipboardItem>> = clipboardRepo.getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live file transfer history from Room (newest first) */
    val transferItems: StateFlow<List<TransferItem>> = transferRepo.getAllTransfers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Live list of trusted Windows devices — shown in the Trusted Devices popup */
    val trustedDevices: StateFlow<List<TrustedDevice>> = trustedDeviceDao.getAllDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Text currently in the paste text field */
    var pasteText by mutableStateOf("")
        private set

    /** Transient status message shown after send/paste actions */
    var sendStatus by mutableStateOf("")
        private set

    /** Number of connected Windows clients — polled from the service companion */
    var connectedClientsCount by mutableIntStateOf(0)
        private set

    /** User-friendly storage location description */
    var storagePathDescription by mutableStateOf(StoragePreferences.getStoragePathDescription(application))
        private set

    /** Active tab index: 0 = Clipboard, 1 = File Transfers */
    var selectedTabIndex by mutableIntStateOf(0)
        private set

    /** Pairing request from a new Windows client */
    val pendingConnectionRequest: StateFlow<SyncForegroundService.ConnectionRequest?> = SyncForegroundService.pendingConnectionRequest

    init {
        // Poll connected clients count every second
        viewModelScope.launch {
            while (true) {
                connectedClientsCount = SyncForegroundService.connectedClientsCount
                delay(1_000)
            }
        }
    }

    // ── Navigation actions ──────────────────────────────────────────────

    fun setTabIndex(index: Int) {
        selectedTabIndex = index
    }

    // ── Clipboard actions ───────────────────────────────────────────────

    fun onPasteTextChanged(text: String) {
        pasteText = text
    }

    fun readAndSendClipboard(clipboardManager: ClipboardManager) {
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString()

        if (text.isNullOrBlank()) {
            showStatus("Clipboard is empty")
            return
        }

        viewModelScope.launch {
            sendToWindows(text)
            showStatus("Sent: ${text.take(30)}...")
        }
    }

    fun pasteFromClipboard(clipboardManager: ClipboardManager) {
        val clip = clipboardManager.primaryClip
        val text = clip?.getItemAt(0)?.text?.toString() ?: ""
        pasteText = text
    }

    fun copyToClipboard(clipboardManager: ClipboardManager, text: String) {
        if (text.isEmpty()) return
        val clip = android.content.ClipData.newPlainText("DeviceSync", text)
        clipboardManager.setPrimaryClip(clip)
        pasteText = text
        showStatus("Copied to clipboard ✓")
    }

    fun sendPasteText() {
        if (pasteText.isBlank()) return

        val text = pasteText
        viewModelScope.launch {
            sendToWindows(text)
            showStatus("Sent ✓")
            pasteText = ""
        }
    }

    // ── File Transfer actions ───────────────────────────────────────────

    /**
     * Stage and send selected files (photos, videos, docs) to Windows PC.
     */
    fun sendFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return

        viewModelScope.launch {
            showStatus("Staging ${uris.size} file(s)...")
            val transfers = FileTransferManager.registerTransfers(getApplication(), uris)
            if (transfers.isNotEmpty()) {
                val sizeTotal = transfers.sumOf { it.size }
                val formatted = FormatUtils.formatBytes(sizeTotal)
                showStatus("Sent ${transfers.size} file(s) ($formatted) ⚡")
                selectedTabIndex = 1 // Switch to transfers tab to view progress
            } else {
                showStatus("Failed to prepare files")
            }
        }
    }

    // ── Trusted Devices ──────────────────────────────────────────

    /** Remove a trusted device — next connection from that PC will re-prompt approval. */
    fun revokeTrustedDevice(deviceId: String) {
        viewModelScope.launch {
            trustedDeviceDao.deleteById(deviceId)
        }
    }

    // ── Storage Preferences ─────────────────────────────────────────────

    fun updateCustomStorageUri(treeUri: Uri?) {
        StoragePreferences.setCustomStorageUri(getApplication(), treeUri)
        refreshStorageDescription()
    }

    fun resetStorageToDefault() {
        StoragePreferences.setCustomStorageUri(getApplication(), null)
        refreshStorageDescription()
    }

    private fun refreshStorageDescription() {
        storagePathDescription = StoragePreferences.getStoragePathDescription(getApplication())
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun sendToWindows(text: String) {
        val newId = UUID.randomUUID().toString()
        val inserted = clipboardRepo.addItem(
            content = text,
            source = "android",
            clipboardId = newId
        )

        if (inserted) {
            val payload = ClipboardPayload(
                clipboardId = newId,
                source = "android",
                content = text
            )
            SyncForegroundService.sendToAllClients(gson.toJson(payload))
        }
    }

    private fun showStatus(message: String) {
        sendStatus = message
        viewModelScope.launch {
            delay(3_000)
            if (sendStatus == message) sendStatus = ""
        }
    }

}
