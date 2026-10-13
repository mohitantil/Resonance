package com.example.devicesync.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devicesync.data.TransferItem
import com.example.devicesync.ui.components.*
import com.example.devicesync.ui.viewmodel.SyncViewModel

/**
 * Main sync screen — assembles UI components and wires them to the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipboardItems by viewModel.clipboardItems.collectAsState()
    val transferItems by viewModel.transferItems.collectAsState()
    val connectionRequest by viewModel.pendingConnectionRequest.collectAsState()
    val trustedDevices by viewModel.trustedDevices.collectAsState()
    var showTrustedDevices by remember { mutableStateOf(false) }

    // ── Modern Pickers ──
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.sendFiles(uris)
            }
        }
    )

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                viewModel.sendFiles(uris)
            }
        }
    )

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { treeUri ->
            if (treeUri != null) {
                viewModel.updateCustomStorageUri(treeUri)
            }
        }
    )

    Scaffold(
        containerColor = SyncColors.Base,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Local Sync Agent",
                                color = SyncColors.Text,
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp
                            )
                            Text(
                                text = "Android ↔ Windows",
                                color = SyncColors.Overlay,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SyncColors.Base
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // ── Connection status card ──
            ConnectionStatusCard(connectedClients = viewModel.connectedClientsCount)

            Spacer(modifier = Modifier.height(10.dp))

            // ── Mode Tab Selector ──
            ModeTabSelector(
                selectedIndex = viewModel.selectedTabIndex,
                onTabSelected = viewModel::setTabIndex
            )

            // ── Transient status message ──
            StatusMessage(
                message = viewModel.sendStatus,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            connectionRequest?.let { request ->
                AlertDialog(
                    onDismissRequest = { request.onResult(false) },
                    title = { Text("New Device Connection") },
                    text = { Text("Allow connection from ${request.deviceName}?") },
                    confirmButton = {
                        TextButton(onClick = { request.onResult(true) }) {
                            Text("Approve")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { request.onResult(false) }) {
                            Text("Decline")
                        }
                    }
                )
            }

            if (viewModel.selectedTabIndex == 0) {
                // ══════════════════════════════════════════════════════════
                // 📋 CLIPBOARD SYNC VIEW
                // ══════════════════════════════════════════════════════════

                // Quick send button (reads system clipboard on tap)
                SendClipboardButton(
                    onClick = { viewModel.readAndSendClipboard(clipboardManager) }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Trusted Devices button
                OutlinedButton(
                    onClick = { showTrustedDevices = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SyncColors.Mauve
                    )
                ) {
                    Text(
                        text = "🖥️ Trusted Devices  (${trustedDevices.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Paste / type & send card
                PasteAndSendCard(
                    pasteText = viewModel.pasteText,
                    onTextChange = viewModel::onPasteTextChanged,
                    onPasteFromClipboard = { viewModel.pasteFromClipboard(clipboardManager) },
                    onSend = viewModel::sendPasteText
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "📋 Clipboard Journal (last 10)",
                    color = SyncColors.Subtext,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = clipboardItems,
                        key = { it.clipboardId }
                    ) { item ->
                        ClipboardHistoryCard(
                            item = item,
                            onCopy = { text -> viewModel.copyToClipboard(clipboardManager, text) }
                        )
                    }

                    if (clipboardItems.isEmpty()) {
                        item { ClipboardEmptyState() }
                    }
                }
            } else if (viewModel.selectedTabIndex == 1) {
                // ══════════════════════════════════════════════════════════
                // 📁 FILE TRANSFERS VIEW
                // ══════════════════════════════════════════════════════════

                // Quick send actions
                FileTransferActionsRow(
                    onPickPhotos = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onPickDocs = {
                        docPickerLauncher.launch(arrayOf("*/*"))
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Storage location settings card
                StorageSettingsCard(
                    storagePathDescription = viewModel.storagePathDescription,
                    onChangeFolder = { folderPickerLauncher.launch(null) },
                    onResetDefault = viewModel::resetStorageToDefault
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "📁 Transfer Activity",
                    color = SyncColors.Subtext,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = transferItems,
                        key = { it.transferId }
                    ) { item ->
                        TransferHistoryCard(
                            item = item,
                            onOpen = { openFile(context, it) }
                        )
                    }

                    if (transferItems.isEmpty()) {
                        item { TransferEmptyState() }
                    }
                }
            }
        }
    }

    // ── Trusted Devices popup ──
    if (showTrustedDevices) {
        AlertDialog(
            onDismissRequest = { showTrustedDevices = false },
            containerColor = SyncColors.Surface0,
            title = {
                Text(
                    "🖥️ Trusted Windows PCs",
                    color = SyncColors.Text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column {
                    if (trustedDevices.isEmpty()) {
                        Text(
                            "No trusted devices yet. Approve a connection from Windows to add one.",
                            color = SyncColors.Subtext,
                            fontSize = 13.sp
                        )
                    } else {
                        trustedDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        device.deviceName,
                                        color = SyncColors.Text,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "ID: ${device.deviceId.take(8)}…",
                                        color = SyncColors.Overlay,
                                        fontSize = 10.sp
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.revokeTrustedDevice(device.deviceId) }
                                ) {
                                    Text(
                                        "Revoke",
                                        color = SyncColors.Red,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            HorizontalDivider(color = SyncColors.Surface0)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTrustedDevices = false }) {
                    Text("Done", color = SyncColors.Mauve)
                }
            }
        )
    }
}

private fun openFile(context: Context, item: TransferItem) {
    val pathString = item.localUriOrPath ?: return
    try {
        val uri = Uri.parse(pathString)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(viewIntent)
    } catch (e: Exception) {
        Log.e("SyncScreen", "Unable to open file ${item.fileName}", e)
    }
}
