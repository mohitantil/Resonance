package com.example.devicesync.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.devicesync.FormatUtils
import com.example.devicesync.data.ClipboardItem
import com.example.devicesync.data.TransferItem
import java.text.SimpleDateFormat
import java.util.*

// ─── Catppuccin Mocha palette ───────────────────────────────────────────

object SyncColors {
    val Base = Color(0xFF1E1E2E)
    val Surface0 = Color(0xFF313244)
    val Surface1 = Color(0xFF45475A)
    val Text = Color(0xFFCDD6F4)
    val Subtext = Color(0xFFA6ADC8)
    val Overlay = Color(0xFF6C7086)
    val Green = Color(0xFFA6E3A1)
    val Red = Color(0xFFF38BA8)
    val Blue = Color(0xFF89B4FA)
    val Mauve = Color(0xFFCBA6F7)
    val Yellow = Color(0xFFF9E2AF)
    val Peach = Color(0xFFFAB387)
}

// ─── Connection Status Card ─────────────────────────────────────────────

@Composable
fun ConnectionStatusCard(
    connectedClients: Int,
    modifier: Modifier = Modifier
) {
    val isConnected = connectedClients > 0

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SyncColors.Surface0)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) SyncColors.Green else SyncColors.Red)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isConnected) "Windows Connected ✓" else "Waiting for Windows...",
                    color = SyncColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    text = if (isConnected) "$connectedClients client(s) connected via WebSocket"
                    else "Connect to phone hotspot and start Windows agent",
                    color = SyncColors.Overlay,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ─── Mode Tab Selector ──────────────────────────────────────────────────

@Composable
fun ModeTabSelector(
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    PrimaryTabRow(
        selectedTabIndex = selectedIndex,
        containerColor = SyncColors.Surface0,
        contentColor = SyncColors.Mauve,
        modifier = modifier.clip(RoundedCornerShape(10.dp))
    ) {
        Tab(
            selected = selectedIndex == 0,
            onClick = { onTabSelected(0) },
            text = { Text("📋 Clipboard", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) },
            selectedContentColor = SyncColors.Mauve,
            unselectedContentColor = SyncColors.Subtext
        )
        Tab(
            selected = selectedIndex == 1,
            onClick = { onTabSelected(1) },
            text = { Text("📁 Transfers", fontWeight = FontWeight.SemiBold, fontSize = 13.sp) },
            selectedContentColor = SyncColors.Mauve,
            unselectedContentColor = SyncColors.Subtext
        )
    }
}

// ─── File Transfer Actions Row ──────────────────────────────────────────

@Composable
fun FileTransferActionsRow(
    onPickPhotos: () -> Unit,
    onPickDocs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onPickPhotos,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SyncColors.Blue)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Photos & Videos",
                tint = SyncColors.Base,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Photos & Videos",
                color = SyncColors.Base,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        Button(
            onClick = onPickDocs,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SyncColors.Mauve)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = "Documents",
                tint = SyncColors.Base,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Documents",
                color = SyncColors.Base,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

// ─── Storage Settings Card ──────────────────────────────────────────────

@Composable
fun StorageSettingsCard(
    storagePathDescription: String,
    onChangeFolder: () -> Unit,
    onResetDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCustom = storagePathDescription.startsWith("Custom:")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SyncColors.Surface0)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    tint = SyncColors.Peach,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Storage Location",
                    color = SyncColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = storagePathDescription,
                color = SyncColors.Overlay,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onChangeFolder,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SyncColors.Peach)
                ) {
                    Text("Change Folder", fontSize = 12.sp)
                }

                if (isCustom) {
                    TextButton(
                        onClick = onResetDefault,
                        colors = ButtonDefaults.textButtonColors(contentColor = SyncColors.Subtext)
                    ) {
                        Text("Reset to Default", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ─── File Transfer History Card ─────────────────────────────────────────

@Composable
fun TransferHistoryCard(
    item: TransferItem,
    onOpen: (TransferItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val isSent = item.direction == "sent"

    val iconText = when {
        item.mimeType.startsWith("image/") -> "🖼️"
        item.mimeType.startsWith("video/") -> "🎬"
        item.mimeType.startsWith("audio/") -> "🎵"
        item.mimeType.contains("pdf") -> "📕"
        item.mimeType.contains("zip") || item.mimeType.contains("rar") -> "📦"
        else -> "📄"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !item.localUriOrPath.isNullOrBlank()) { onOpen(item) },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SyncColors.Surface1)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = iconText, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    color = SyncColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isSent) "↑ Sent to PC" else "↓ Received",
                        color = if (isSent) SyncColors.Blue else SyncColors.Green,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = FormatUtils.formatBytes(item.fileSize),
                        color = SyncColors.Subtext,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeFormat.format(Date(item.timestamp)),
                        color = SyncColors.Overlay,
                        fontSize = 11.sp
                    )
                }
            }

            if (!item.localUriOrPath.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open",
                    tint = SyncColors.Subtext,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ─── Send Clipboard Button ──────────────────────────────────────────────

@Composable
fun SendClipboardButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SyncColors.Mauve)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            tint = SyncColors.Base
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Send Clipboard to PC",
            color = SyncColors.Base,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

// ─── Paste & Send Text Field Card ───────────────────────────────────────

@Composable
fun PasteAndSendCard(
    pasteText: String,
    onTextChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SyncColors.Surface0)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Or paste / type text to send:",
                color = SyncColors.Subtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = pasteText,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste text here...", color = SyncColors.Overlay) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SyncColors.Text,
                    unfocusedTextColor = SyncColors.Text,
                    cursorColor = SyncColors.Mauve,
                    focusedBorderColor = SyncColors.Mauve,
                    unfocusedBorderColor = SyncColors.Surface1
                ),
                maxLines = 3,
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPasteFromClipboard,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SyncColors.Blue)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Paste", fontSize = 13.sp)
                }

                Button(
                    onClick = onSend,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    enabled = pasteText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SyncColors.Green,
                        disabledContainerColor = SyncColors.Surface1
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = SyncColors.Base,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send", color = SyncColors.Base, fontSize = 13.sp)
                }
            }
        }
    }
}

// ─── Clipboard History Card ─────────────────────────────────────────────

@Composable
fun ClipboardHistoryCard(
    item: ClipboardItem,
    onCopy: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val isAndroid = item.source == "android"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = onCopy != null) { onCopy?.invoke(item.content) },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SyncColors.Surface1)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.content,
                    color = SyncColors.Text,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Text(
                        text = if (isAndroid) "📱 Android" else "💻 Windows",
                        color = if (isAndroid) SyncColors.Blue else SyncColors.Mauve,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = timeFormat.format(Date(item.timestamp)),
                        color = SyncColors.Overlay,
                        fontSize = 11.sp
                    )
                }
            }

            if (onCopy != null) {
                IconButton(onClick = { onCopy(item.content) }) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy to clipboard",
                        tint = SyncColors.Mauve,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ─── Empty States ───────────────────────────────────────────────────────

@Composable
fun ClipboardEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No clipboard items yet.\nCopy something and tap Send!",
            color = SyncColors.Overlay,
            fontSize = 14.sp
        )
    }
}

@Composable
fun TransferEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No file transfers yet.\nTap 'Photos & Videos' or 'Documents' above,\nor share any file from your gallery/files app!",
            color = SyncColors.Overlay,
            fontSize = 14.sp
        )
    }
}

// ─── Status Toast ───────────────────────────────────────────────────────

@Composable
fun StatusMessage(
    message: String,
    modifier: Modifier = Modifier
) {
    if (message.isNotBlank()) {
        Text(
            text = message,
            color = SyncColors.Green,
            fontSize = 13.sp,
            modifier = modifier.padding(start = 4.dp)
        )
    }
}
