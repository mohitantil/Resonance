package com.example.devicesync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.devicesync.crypto.SessionCrypto
import com.example.devicesync.data.ClipboardRepository
import com.example.devicesync.data.StoragePreferences
import com.example.devicesync.data.SyncDatabase
import com.example.devicesync.data.TransferRepository
import com.example.devicesync.data.TrustedDevice
import com.example.devicesync.data.TrustedDeviceDao
import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.*

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.google.gson.JsonParser

class SyncForegroundService : Service() {

    /**
     * Holds a WebSocket session alongside its per-session ECDHE crypto state.
     */
    data class ClientSession(
        val session: WebSocketSession,
        val crypto: SessionCrypto = SessionCrypto(),
        val sessionToken: String = UUID.randomUUID().toString()
    )

    data class ConnectionRequest(
        val deviceId: String,
        val deviceName: String,
        val onResult: (Boolean) -> Unit
    )

    companion object {
        private const val TAG = "SyncService"
        private const val CHANNEL_ID = "sync_channel"
        private const val TRANSFER_CHANNEL_ID = "transfer_channel"
        private const val CLIPBOARD_CHANNEL_ID = "clipboard_channel"
        private const val NOTIFICATION_ID = 1
        private const val CLIPBOARD_NOTIFICATION_ID = 2
        private const val PORT = 7777

        // Shared: the Activity reads this to show connection status and send clipboard
        private val _connectedClients = ConcurrentHashMap<String, ClientSession>()
        private val _gson = Gson()

        val pendingConnectionRequest = kotlinx.coroutines.flow.MutableStateFlow<ConnectionRequest?>(null)

        /** Number of connected Windows clients (observed by the UI) */
        val connectedClientsCount: Int
            get() = _connectedClients.size

        /** Send a JSON payload to all connected Windows clients via WebSocket */
        suspend fun sendToAllClients(json: String) {
            _connectedClients.values.forEach { clientSession ->
                try {
                    clientSession.session.send(Frame.Text(json))
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending to WebSocket client", e)
                }
            }
        }

        /** Send an encrypted JSON payload to all clients that have completed key exchange */
        suspend fun sendEncryptedToAllClients(json: String) {
            _connectedClients.values.forEach { clientSession ->
                try {
                    if (clientSession.crypto.isEstablished) {
                        val encrypted = clientSession.crypto.encrypt(json)
                        val envelope = _gson.toJson(mapOf(
                            "type" to "encrypted",
                            "payload" to encrypted
                        ))
                        clientSession.session.send(Frame.Text(envelope))
                    } else {
                        // Fallback: send unencrypted if key exchange hasn't happened yet
                        clientSession.session.send(Frame.Text(json))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending encrypted to WebSocket client", e)
                }
            }
        }

        /** Proactively notify connected Windows clients that new files are available to download */
        fun notifyFilesAvailable(transfers: List<FileTransferManager.TransferInfo>) {
            val payload = mapOf(
                "type" to "files_available",
                "count" to transfers.size,
                "transfers" to transfers.map {
                    mapOf(
                        "token" to it.token,
                        "fileName" to it.fileName,
                        "mimeType" to it.mimeType,
                        "size" to it.size
                    )
                }
            )
            val json = _gson.toJson(payload)
            CoroutineScope(Dispatchers.IO).launch {
                sendToAllClients(json)
            }
        }

        /** Get the crypto instance for a specific session */
        fun getCryptoForSession(sessionId: String): SessionCrypto? {
            return _connectedClients[sessionId]?.crypto
        }
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var clipboardRepo: ClipboardRepository
    private lateinit var transferRepo: TransferRepository
    private lateinit var trustedDeviceDao: TrustedDeviceDao
    private lateinit var clipboardManager: ClipboardManager
    private val gson = Gson()

    // Convenience alias for instance methods to use the shared map
    private val connectedClients get() = _connectedClients

    // The last clipboard content we set ourselves — used for loop prevention
    @Volatile
    private var lastSetContent: String? = null

    // Discovery components
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var udpSocket: DatagramSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Initialize database and repositories
        val db = SyncDatabase.getInstance(applicationContext)
        clipboardRepo = ClipboardRepository(db.clipboardDao())
        transferRepo = TransferRepository(db.transferDao())
        trustedDeviceDao = db.trustedDeviceDao()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        startSignalingServer()
        startUdpBeaconResponder()
        registerNsdService()
        startClipboardListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        server?.stop(1000, 2000)
        try {
            udpSocket?.close()
            udpSocket = null
        } catch (_: Exception) {}
        unregisterNsdService()
    }

    private fun registerNsdService() {
        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "DeviceSync-${Build.MODEL ?: "Android"}"
                serviceType = "_devicesync._tcp."
                port = PORT
            }
            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "NSD service registered: ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "NSD service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD unregistration failed: $errorCode")
                }
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service: ${e.message}")
        }
    }

    private fun unregisterNsdService() {
        try {
            nsdRegistrationListener?.let { nsdManager?.unregisterService(it) }
            nsdRegistrationListener = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NSD service: ${e.message}")
        }
    }

    private fun startUdpBeaconResponder() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(PORT)
                udpSocket = socket
                val buffer = ByteArray(1024)
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (msg.contains("devicesync_ping") || msg.contains("discover")) {
                        val responseJson = gson.toJson(mapOf(
                            "type" to "devicesync_pong",
                            "device" to (Build.MODEL ?: "Android"),
                            "port" to PORT,
                            "protocol" to "2.0"
                        ))
                        val sendData = responseJson.toByteArray(Charsets.UTF_8)
                        val replyPacket = DatagramPacket(sendData, sendData.size, packet.socketAddress)
                        socket.send(replyPacket)
                        Log.d(TAG, "Replied to UDP discovery ping from ${packet.socketAddress}")
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.d(TAG, "UDP beacon responder ended: ${e.message}")
                }
            }
        }
    }

    // ─── Ktor Signaling Server ──────────────────────────────────────────

    private fun startSignalingServer() {
        serviceScope.launch {
            try {
                server = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
                    install(WebSockets)

                    routing {
                        // Discovery endpoint
                        get("/hello") {
                            call.respondText(
                                gson.toJson(
                                    mapOf(
                                        "status" to "ok",
                                        "device" to (Build.MODEL ?: "android"),
                                        "protocol" to "2.0",
                                        "features" to listOf(
                                            "clipboard", "file_transfer", "file_upload", "e2ee"
                                        )
                                    )
                                ),
                                ContentType.Application.Json
                            )
                        }


                        // ─── File Transfer Endpoints (Android -> Windows) ────

                        // List all active file transfers
                        get("/transfers") {
                            val authHeader = call.request.headers["Authorization"]
                            val token = authHeader?.removePrefix("Bearer ")
                            if (token == null || !connectedClients.values.any { it.sessionToken == token }) {
                                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@get
                            }

                            val transfers = FileTransferManager.listTransfers().map {
                                mapOf(
                                    "token" to it.token,
                                    "fileName" to it.fileName,
                                    "mimeType" to it.mimeType,
                                    "size" to it.size
                                )
                            }
                            call.respondText(gson.toJson(transfers), ContentType.Application.Json)
                        }

                        // Download a file by transfer token
                        get("/transfer/{token}") {
                            val authHeader = call.request.headers["Authorization"]
                            val sessionToken = authHeader?.removePrefix("Bearer ")
                            if (sessionToken == null || !connectedClients.values.any { it.sessionToken == sessionToken }) {
                                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@get
                            }

                            val token = call.parameters["token"]
                            if (token == null) {
                                call.respondText("""{"error":"missing token"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                                return@get
                            }

                            val info = FileTransferManager.getTransfer(token)
                            if (info == null || !info.file.exists()) {
                                call.respondText("""{"error":"not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                                return@get
                            }

                            try {
                                call.response.header("Content-Disposition", "attachment; filename=\"${info.fileName}\"")
                                call.response.header("Content-Length", info.size.toString())
                                call.respondFile(info.file)
                                Log.i(TAG, "File transfer served: ${info.fileName}")
                            } catch (e: Exception) {
                                Log.e(TAG, "File transfer error for token $token", e)
                                call.respondText("""{"error":"transfer failed"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }

                        // Signal that file download is complete and staged cache can be cleaned up
                        delete("/transfer/{token}") {
                            val authHeader = call.request.headers["Authorization"]
                            val sessionToken = authHeader?.removePrefix("Bearer ")
                            if (sessionToken == null || !connectedClients.values.any { it.sessionToken == sessionToken }) {
                                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@delete
                            }

                            val token = call.parameters["token"]
                            if (token != null) {
                                FileTransferManager.removeTransfer(token)
                                call.respondText("""{"status":"deleted"}""", ContentType.Application.Json)
                            } else {
                                call.respondText("""{"error":"missing token"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                            }
                        }

                        // ─── File Upload Endpoint (Windows -> Android) ──────

                        post("/upload") {
                            val authHeader = call.request.headers["Authorization"]
                            val sessionToken = authHeader?.removePrefix("Bearer ")
                            if (sessionToken == null || !connectedClients.values.any { it.sessionToken == sessionToken }) {
                                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@post
                            }

                            try {
                                val rawFileName = call.request.header("X-File-Name") ?: "file_${System.currentTimeMillis()}"
                                val decodedFileName = java.net.URLDecoder.decode(rawFileName, "UTF-8")
                                val fileName = java.io.File(decodedFileName).name
                                val mimeType = call.request.header("Content-Type") ?: "application/octet-stream"
                                val fileSize = call.request.header("X-File-Size")?.toLongOrNull() ?: -1L

                                val destination = StoragePreferences.openDestinationStream(
                                    applicationContext, fileName, mimeType
                                )

                                if (destination == null) {
                                    call.respondText("""{"error":"cannot create destination file"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                                    return@post
                                }

                                updateNotification("Receiving: $fileName...")

                                val channel = call.receiveChannel()
                                val buffer = ByteArray(64 * 1024)
                                var totalRead = 0L

                                destination.outputStream.use { outStream ->
                                    while (!channel.isClosedForRead) {
                                        val read = channel.readAvailable(buffer, 0, buffer.size)
                                        if (read <= 0) break
                                        outStream.write(buffer, 0, read)
                                        totalRead += read

                                        if (fileSize > 0) {
                                            val pct = (totalRead * 100 / fileSize).toInt()
                                            updateNotification("Receiving $fileName: $pct%")
                                        }
                                    }
                                    outStream.flush()
                                }

                                StoragePreferences.finalizeDestination(applicationContext, destination)
                                updateNotification("Received: $fileName ✓")
                                showFileReceivedNotification(fileName, destination.uri, mimeType)

                                // Log into transfer history
                                transferRepo.addTransfer(
                                    fileName = fileName,
                                    fileSize = if (totalRead > 0) totalRead else fileSize,
                                    mimeType = mimeType,
                                    direction = "received",
                                    status = "completed",
                                    localUriOrPath = destination.uri.toString()
                                )

                                call.respondText(
                                    """{"status":"received","fileName":"$fileName","bytes":$totalRead}""",
                                    ContentType.Application.Json
                                )
                                Log.i(TAG, "Successfully received and saved upload: $fileName ($totalRead bytes)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling file upload", e)
                                call.respondText(
                                    """{"error":"upload failed: ${e.message}"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.InternalServerError
                                )
                            }
                        }

                        // WebSocket: real-time bidirectional sync with typed message routing
                        webSocket("/sync") {
                            val sessionId = UUID.randomUUID().toString()
                            val clientSession = ClientSession(session = this)
                            connectedClients[sessionId] = clientSession
                            Log.i(TAG, "Windows client connected: $sessionId")
                            updateNotification("Windows connected ✓")

                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        try {
                                            routeMessage(sessionId, text)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error routing WebSocket message", e)
                                        }
                                    }
                                }
                            } finally {
                                clientSession.crypto.reset()
                                connectedClients.remove(sessionId)
                                Log.i(TAG, "Windows client disconnected: $sessionId")
                                updateNotification("Windows disconnected")
                            }
                        }
                    }
                }.start(wait = false)
                Log.i(TAG, "Signaling server started on port $PORT")
                updateNotification("Server running on :$PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
            }
        }
    }

    // ─── Typed Message Router ────────────────────────────────────────────

    /**
     * Routes an incoming WebSocket message by its "type" field.
     * Messages without a type are treated as legacy clipboard payloads for backward compat.
     */
    private suspend fun routeMessage(sessionId: String, rawJson: String) {
        val jsonObj = try {
            JsonParser.parseString(rawJson).asJsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Non-JSON WebSocket message: $rawJson")
            return
        }

        val type = jsonObj.get("type")?.asString

        when (type) {
            // ── ECDHE Key Exchange ──
            "key_exchange" -> {
                val remotePubKey = jsonObj.get("pubKey")?.asString ?: return
                val deviceId = jsonObj.get("deviceId")?.asString ?: "unknown"
                val deviceName = jsonObj.get("deviceName")?.asString ?: "Unknown PC"
                val clientSession = connectedClients[sessionId] ?: return

                val isTrusted = trustedDeviceDao.countById(deviceId) > 0

                if (!isTrusted) {
                    val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    pendingConnectionRequest.value = ConnectionRequest(deviceId, deviceName) { result ->
                        deferred.complete(result)
                        pendingConnectionRequest.value = null
                    }
                    val approved = deferred.await()
                    if (!approved) {
                        clientSession.session.close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "Connection rejected"))
                        return
                    }
                    // Persist the newly approved device in Room
                    trustedDeviceDao.insert(
                        TrustedDevice(
                            deviceId = deviceId,
                            deviceName = deviceName,
                            approvedAt = System.currentTimeMillis()
                        )
                    )
                }

                // Generate our keypair and derive the shared secret
                clientSession.crypto.generateKeyPair()
                clientSession.crypto.deriveSharedSecret(remotePubKey)

                // Send our public key and session token back
                val response = gson.toJson(mapOf(
                    "type" to "key_exchange",
                    "pubKey" to clientSession.crypto.getPublicKeyBase64(),
                    "sessionToken" to clientSession.sessionToken
                ))
                clientSession.session.send(io.ktor.websocket.Frame.Text(response))
                Log.i(TAG, "Key exchange completed with session $sessionId")
                updateNotification("🔒 Encrypted session established")
            }

            // ── Encrypted envelope — decrypt and re-route ──
            "encrypted" -> {
                val encryptedPayload = jsonObj.get("payload")?.asString ?: return
                val clientSession = connectedClients[sessionId] ?: return
                if (!clientSession.crypto.isEstablished) {
                    Log.w(TAG, "Received encrypted message but no key exchange done")
                    return
                }
                val decrypted = clientSession.crypto.decrypt(encryptedPayload)
                routeMessage(sessionId, decrypted) // re-route the decrypted inner message
            }

            // ── Clipboard sync (typed) ──
            "clipboard" -> {
                val clipboardId = jsonObj.get("clipboardId")?.asString ?: return
                val source = jsonObj.get("source")?.asString ?: return
                val content = jsonObj.get("content")?.asString ?: return
                handleClipboardMessage(clipboardId, source, content)
            }

            // ── Ping / Pong keep-alive ──
            "ping" -> {
                val clientSession = connectedClients[sessionId] ?: return
                val pong = gson.toJson(mapOf("type" to "pong"))
                clientSession.session.send(Frame.Text(pong))
                Log.d(TAG, "Ping received from $sessionId — pong sent")
            }

            // ── File transfer notification ──
            "files_available" -> {
                // This is outbound-only from Android; ignore if received
                Log.d(TAG, "Ignoring inbound files_available")
            }

            // ── Legacy/untyped — treat as clipboard payload for backward compat ──
            null -> {
                val payload = gson.fromJson(rawJson, ClipboardPayload::class.java)
                if (payload.clipboardId != null && payload.content != null && payload.source != null) {
                    handleClipboardMessage(payload.clipboardId, payload.source, payload.content)
                } else {
                    Log.w(TAG, "Unknown untyped message: ${rawJson.take(100)}")
                }
            }

            else -> {
                Log.w(TAG, "Unknown message type: $type")
            }
        }
    }

    /**
     * Process a clipboard sync message (extracted from both typed and legacy paths).
     */
    private suspend fun handleClipboardMessage(clipboardId: String, source: String, content: String) {
        val inserted = clipboardRepo.addItem(
            content = content,
            source = source,
            clipboardId = clipboardId
        )
        if (inserted) {
            lastSetContent = content
            withContext(Dispatchers.Main) {
                try {
                    val clip = ClipData.newPlainText("sync", content)
                    clipboardManager.setPrimaryClip(clip)
                } catch (e: Exception) {
                    Log.w(TAG, "Direct setPrimaryClip skipped by OS (requires user focus): ${e.message}")
                }
            }
            updateNotification("Received: ${content.take(30)}...")
            showClipboardReceivedNotification(content)
        }
    }

    // ─── Android Clipboard Listener ─────────────────────────────────────

    private fun startClipboardListener() {
        clipboardManager.addPrimaryClipChangedListener {
            serviceScope.launch {
                try {
                    val clip = withContext(Dispatchers.Main) {
                        clipboardManager.primaryClip
                    }
                    val text = clip?.getItemAt(0)?.text?.toString() ?: return@launch

                    if (text == lastSetContent) {
                        lastSetContent = null
                        return@launch
                    }

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
                        val json = gson.toJson(payload)
                        connectedClients.values.forEach { clientSession ->
                            try {
                                clientSession.session.send(Frame.Text(json))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error sending to WebSocket client", e)
                            }
                        }
                        updateNotification("Sent: ${text.take(30)}...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clipboard listener", e)
                }
            }
        }
    }

    // ─── Notification Helpers ───────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Ongoing sync service channel
            val syncChannel = NotificationChannel(
                CHANNEL_ID,
                "Sync Agent Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Local Device Sync Agent background service"
            }
            manager.createNotificationChannel(syncChannel)

            // High priority channel for completed file transfers
            val transferChannel = NotificationChannel(
                TRANSFER_CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for received files"
            }
            manager.createNotificationChannel(transferChannel)

            // High priority channel for clipboard sync items
            val clipboardChannel = NotificationChannel(
                CLIPBOARD_CHANNEL_ID,
                "Clipboard Sync",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications with quick copy action for text received from PC"
            }
            manager.createNotificationChannel(clipboardChannel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local Sync Agent")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = createNotification(statusText)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun showFileReceivedNotification(fileName: String, uri: Uri, mimeType: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            fileName.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TRANSFER_CHANNEL_ID)
            .setContentTitle("File Received 📥")
            .setContentText("$fileName from Windows PC")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify((System.currentTimeMillis() % 10000).toInt() + 100, notification)
    }

    private fun showClipboardReceivedNotification(content: String) {
        val copyIntent = Intent(this, ClipboardCopyActivity::class.java).apply {
            putExtra(ClipboardCopyActivity.EXTRA_CONTENT, content)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingCopyIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % 10000).toInt(),
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingOpenAppIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CLIPBOARD_CHANNEL_ID)
            .setContentTitle("📋 Windows Clipboard")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_menu_set_as)
            .setContentIntent(pendingOpenAppIntent)
            .addAction(
                android.R.drawable.ic_menu_set_as,
                "📋 Copy to Clipboard",
                pendingCopyIntent
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(CLIPBOARD_NOTIFICATION_ID, notification)
    }
}

/** Simple data class for JSON serialization of clipboard payloads */
data class ClipboardPayload(
    val clipboardId: String? = null,
    val source: String? = null,
    val content: String? = null,
    val type: String = "clipboard"
)
