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

import android.net.nsd.NsdManager

import android.net.nsd.NsdServiceInfo

import android.net.wifi.WifiManager

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

import com.google.gson.JsonParser

import io.ktor.http.*

import io.ktor.server.application.*

import io.ktor.server.engine.*

import io.ktor.server.netty.*

import io.ktor.server.plugins.origin

import io.ktor.server.request.*

import io.ktor.server.response.*

import io.ktor.server.routing.*

import io.ktor.server.websocket.*

import io.ktor.utils.io.*

import io.ktor.utils.io.jvm.javaio.toInputStream

import io.ktor.websocket.*

import kotlinx.coroutines.*

import java.net.DatagramPacket

import java.net.DatagramSocket

import java.net.NetworkInterface

import java.net.URLEncoder

import java.util.UUID

import java.util.concurrent.ConcurrentHashMap



class SyncForegroundService : Service() {



    data class ClientSession(

        val session: WebSocketSession,

        val remoteIp: String,

        val isHotspot: Boolean,

        val crypto: SessionCrypto = SessionCrypto(),

        val sessionToken: String = UUID.randomUUID().toString(),

        @Volatile var deviceId: String = "",

        @Volatile var isApproved: Boolean = false

    )



    data class ConnectionRequest(

        val sessionId: String,

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



        private val _connectedClients = ConcurrentHashMap<String, ClientSession>()
        private val _gson = Gson()

        // Tracks the last clipboard text written programmatically by DeviceSync (prevents echo loops)
        @Volatile
        private var lastProgrammaticClip: String? = null

        fun markLocalClipboardWrite(content: String) {
            lastProgrammaticClip = content
        }

        val pendingConnectionRequest = kotlinx.coroutines.flow.MutableStateFlow<ConnectionRequest?>(null)

        val connectedClientsCount: Int
            get() = _connectedClients.values.count { it.isApproved && it.crypto.isEstablished }

        /**
         * Immediately revokes and closes any active WebSocket/HTTP sessions for the given deviceId (fixes SEC-01).
         */
        fun revokeAndDisconnectDevice(deviceId: String) {
            val matching = _connectedClients.entries.filter { it.value.deviceId == deviceId }
            matching.forEach { (sessionId, clientSession) ->
                clientSession.isApproved = false
                clientSession.crypto.reset()
                _connectedClients.remove(sessionId)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        clientSession.session.close(
                            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device trust revoked")
                        )
                    } catch (_: Exception) { }
                }
            }
        }

        fun areAllClientsOnHotspot(): Boolean {

            val active = _connectedClients.values.filter { it.isApproved && it.crypto.isEstablished }

            if (active.isNotEmpty()) {

                return active.all { it.isHotspot }

            }

            return isLocalHotspotInterfaceActive()

        }



        fun isLocalHotspotInterfaceActive(): Boolean {

            return try {

                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()

                interfaces.any { iface ->

                    val name = iface.name.lowercase()

                    val isTetherIface = name.startsWith("ap") || name.startsWith("wlan1") ||

                        name.startsWith("rndis") || name.startsWith("swlan") || name.startsWith("ncm")

                    isTetherIface && iface.isUp && iface.interfaceAddresses.any { it.address.hostAddress != null }

                }

            } catch (_: Exception) {

                false

            }

        }



        fun isHotspotIp(remoteIp: String?): Boolean {

            if (remoteIp.isNullOrBlank()) return false

            if (remoteIp.startsWith("192.168.43.") ||

                remoteIp.startsWith("192.168.137.") ||

                remoteIp.startsWith("172.20.10.")

            ) return true



            return try {

                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()

                interfaces.any { iface ->

                    val name = iface.name.lowercase()

                    val isTetherIface = name.startsWith("ap") || name.startsWith("wlan1") ||

                        name.startsWith("rndis") || name.startsWith("swlan") || name.startsWith("ncm")

                    isTetherIface && iface.interfaceAddresses.any { addr ->

                        val host = addr.address.hostAddress ?: ""

                        val prefix = host.substringBeforeLast('.', "")

                        prefix.isNotEmpty() && remoteIp.startsWith("$prefix.")

                    }

                }

            } catch (_: Exception) {

                false

            }

        }



        /**

         * Sends an AES-256-GCM encrypted JSON payload ONLY to approved, key-exchanged clients.

         * Never falls back to plaintext (fixes SEC-01 and SEC-03).

         */

        suspend fun sendEncryptedToAllClients(json: String) {

            _connectedClients.values.forEach { clientSession ->

                try {

                    if (clientSession.isApproved && clientSession.crypto.isEstablished) {

                        val encrypted = clientSession.crypto.encrypt(json)

                        val envelope = _gson.toJson(

                            mapOf(

                                "type" to "encrypted",

                                "payload" to encrypted

                            )

                        )

                        clientSession.session.send(Frame.Text(envelope))

                    }

                } catch (e: Exception) {

                    Log.e(TAG, "Error sending encrypted frame to WebSocket client", e)

                }

            }

        }



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

                sendEncryptedToAllClients(json)

            }

        }

    }



    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var clipboardRepo: ClipboardRepository

    private lateinit var transferRepo: TransferRepository

    private lateinit var trustedDeviceDao: TrustedDeviceDao

    private lateinit var clipboardManager: ClipboardManager

    private val gson = Gson()



    private val connectedClients get() = _connectedClients



    @Volatile

    private var lastSetContent: String? = null



    private var nsdManager: NsdManager? = null

    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    private var udpSocket: DatagramSocket? = null

    private var multicastLock: WifiManager.MulticastLock? = null



    private fun acquireMulticastLock() {

        try {

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

            multicastLock = wifiManager?.createMulticastLock("Resonance:UdpDiscoveryLock")?.apply {

                setReferenceCounted(false)

                acquire()

            }

            Log.i(TAG, "Wi-Fi MulticastLock acquired for LAN discovery")

        } catch (e: Exception) {

            Log.e(TAG, "Failed to acquire MulticastLock", e)

        }

    }



    override fun onCreate() {

        super.onCreate()

        createNotificationChannels()

        startForeground(NOTIFICATION_ID, createNotification("Starting..."))



        val db = SyncDatabase.getInstance(applicationContext)

        clipboardRepo = ClipboardRepository(db.clipboardDao())

        transferRepo = TransferRepository(db.transferDao())

        trustedDeviceDao = db.trustedDeviceDao()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager



        // Amendment A2: Clean up stale in_progress transfers older than 30 minutes

        serviceScope.launch {

            try {

                val cutoff = System.currentTimeMillis() - 30L * 60L * 1000L

                db.transferDao().failStaleTransfers(cutoff)

            } catch (e: Exception) {

                Log.w(TAG, "Failed to clean up stale transfers", e)

            }

        }



        startSignalingServer()

        startUdpBeaconResponder()

        registerNsdService()

        startClipboardListener()

        acquireMulticastLock()

    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY



    override fun onBind(intent: Intent?): IBinder? = null



    override fun onDestroy() {

        super.onDestroy()

        serviceScope.cancel()

        server?.stop(1000, 2000)

        try { udpSocket?.close(); udpSocket = null } catch (_: Exception) {}

        unregisterNsdService()

        try { multicastLock?.release(); multicastLock = null } catch (_: Exception) {}

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

                val socket = DatagramSocket(null).apply {

                    reuseAddress = true

                    bind(java.net.InetSocketAddress(PORT))

                }

                udpSocket = socket

                val buffer = ByteArray(1024)

                while (isActive && !socket.isClosed) {

                    val packet = DatagramPacket(buffer, buffer.size)

                    socket.receive(packet)

                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    if (msg.contains("devicesync_ping") || msg.contains("discover")) {

                        val responseJson = gson.toJson(

                            mapOf(

                                "type" to "devicesync_pong",

                                "device" to (Build.MODEL ?: "Android"),

                                "port" to PORT,

                                "protocol" to "2.0"

                            )

                        )

                        val sendData = responseJson.toByteArray(Charsets.UTF_8)

                        val replyPacket = DatagramPacket(sendData, sendData.size, packet.socketAddress)

                        socket.send(replyPacket)

                    }

                }

            } catch (e: Exception) {

                if (isActive) Log.d(TAG, "UDP beacon responder ended: ${e.message}")

            }

        }

    }



    private fun startSignalingServer() {

        serviceScope.launch {

            try {

                server = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {

                    install(WebSockets)



                    routing {

                        get("/hello") {

                            val remoteIp = call.request.origin.remoteHost

                            val isHotspot = isHotspotIp(remoteIp)

                            call.respondText(

                                gson.toJson(

                                    mapOf(

                                        "status" to "ok",

                                        "device" to (Build.MODEL ?: "android"),

                                        "protocol" to "2.0",

                                        "networkMode" to if (isHotspot) "HOTSPOT_DIRECT" else "SHARED_WIFI_LAN",

                                        "features" to listOf("clipboard", "file_transfer", "file_upload", "e2ee")

                                    )

                                ),

                                ContentType.Application.Json

                            )

                        }



                        get("/transfers") {

                            val authHeader = call.request.headers["Authorization"]

                            val token = authHeader?.removePrefix("Bearer ")

                            if (token == null || !connectedClients.values.any { it.isApproved && it.sessionToken == token }) {

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



                        get("/transfer/{token}") {

                            val authHeader = call.request.headers["Authorization"]

                            val sessionToken = authHeader?.removePrefix("Bearer ")

                            val clientSession = connectedClients.values.find { it.isApproved && it.sessionToken == sessionToken }

                            if (clientSession == null) {

                                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)

                                return@get

                            }



                            val token = call.parameters["token"] ?: run {

                                call.respondText("""{"error":"missing token"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)

                                return@get

                            }



                            val info = FileTransferManager.getTransfer(token) ?: run {

                                call.respondText("""{"error":"not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)

                                return@get

                            }



                            // Mode A (Hotspot client) = Raw stream; Mode B (Shared Wi-Fi client) = Chunked AES-256-GCM

                            val useEncryption = !clientSession.isHotspot

                            val encodedName = URLEncoder.encode(info.fileName, "UTF-8").replace("+", "%20")

                            val encryptionMode = if (useEncryption) "CHUNKED_AES_GCM" else "NONE"



                            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename*=UTF-8''$encodedName")

                            call.response.header("X-File-Size", info.size.toString())

                            call.response.header("X-Encryption-Mode", encryptionMode)



                            var lastNotifiedPct = -1

                            var lastNotifiedTimeMs = 0L

                            updateNotification("Sending: ${info.fileName}...")



                            try {

                                call.respondOutputStream(

                                    contentType = ContentType.parse(info.mimeType),

                                    status = HttpStatusCode.OK

                                ) {

                                    withContext(Dispatchers.IO) {

                                        FileTransferManager.openStream(applicationContext, token)?.use { inputStream ->

                                            if (useEncryption) {

                                                clientSession.crypto.encryptStreamChunked(inputStream, this@respondOutputStream) { bytesSent ->

                                                    FileTransferManager.updateProgress(

                                                        token = token,

                                                        fileName = info.fileName,

                                                        bytesTransferred = bytesSent,

                                                        totalBytes = info.size,

                                                        status = "in_progress"

                                                    )

                                                    if (info.size > 0L) {

                                                        val pct = ((bytesSent * 100L) / info.size).toInt()

                                                        val now = System.currentTimeMillis()

                                                        if (pct != lastNotifiedPct && (now - lastNotifiedTimeMs >= 500L || pct == 100)) {

                                                            lastNotifiedPct = pct

                                                            lastNotifiedTimeMs = now

                                                            updateNotification("Sending ${info.fileName}: $pct%")

                                                        }

                                                    }

                                                }

                                            } else {

                                                val buffer = ByteArray(64 * 1024)

                                                var totalSent = 0L

                                                while (true) {

                                                    val read = inputStream.read(buffer)

                                                    if (read <= 0) break

                                                    this@respondOutputStream.write(buffer, 0, read)

                                                    totalSent += read

                                                    FileTransferManager.updateProgress(

                                                        token = token,

                                                        fileName = info.fileName,

                                                        bytesTransferred = totalSent,

                                                        totalBytes = info.size,

                                                        status = "in_progress"

                                                    )

                                                    if (info.size > 0L) {

                                                        val pct = ((totalSent * 100L) / info.size).toInt()

                                                        val now = System.currentTimeMillis()

                                                        if (pct != lastNotifiedPct && (now - lastNotifiedTimeMs >= 500L || pct == 100)) {

                                                            lastNotifiedPct = pct

                                                            lastNotifiedTimeMs = now

                                                            updateNotification("Sending ${info.fileName}: $pct%")

                                                        }

                                                    }

                                                }

                                                this@respondOutputStream.flush()

                                            }

                                        } ?: throw IllegalStateException("Cannot open input stream for ${info.uri}")

                                    }

                                }

                                Log.i(TAG, "Streamed ${info.fileName} (${info.size} bytes, mode=$encryptionMode)")

                            } catch (e: Exception) {

                                Log.e(TAG, "Stream interrupted for token $token (will allow client retry)", e)

                                FileTransferManager.updateProgress(token, info.fileName, 0L, info.size, "in_progress")

                                updateNotification("Send interrupted: ${info.fileName} (retrying...)")

                            }

                        }



                        delete("/transfer/{token}") {

                            val authHeader = call.request.headers["Authorization"]

                            val sessionToken = authHeader?.removePrefix("Bearer ")

                            if (sessionToken == null || !connectedClients.values.any { it.isApproved && it.sessionToken == sessionToken }) {

                                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)

                                return@delete

                            }



                            val token = call.parameters["token"]

                            val outcome = call.request.queryParameters["status"] ?: "completed"

                            if (token != null) {

                                val info = FileTransferManager.getTransfer(token)

                                val succeeded = outcome != "failed"

                                FileTransferManager.markTransferFinished(applicationContext, token, success = succeeded)

                                if (info != null) {

                                    updateNotification(if (succeeded) "Sent: ${info.fileName} ✓" else "Send failed: ${info.fileName}")

                                }

                                call.respondText(gson.toJson(mapOf("status" to if (succeeded) "completed" else "failed")), ContentType.Application.Json)

                            } else {

                                call.respondText("""{"error":"missing token"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)

                            }

                        }



                        // ─── File Upload Endpoint (Windows -> Android) ──────

                        post("/upload") {

                            val authHeader = call.request.headers["Authorization"]

                            val sessionToken = authHeader?.removePrefix("Bearer ")

                            val clientSession = connectedClients.values.find { it.isApproved && it.sessionToken == sessionToken }

                            if (clientSession == null) {

                                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)

                                return@post

                            }



                            val rawFileName = call.request.header("X-File-Name") ?: "file_${System.currentTimeMillis()}"

                            val decodedFileName = java.net.URLDecoder.decode(rawFileName, "UTF-8")

                            val fileName = java.io.File(decodedFileName).name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

                            val mimeType = call.request.header("Content-Type") ?: "application/octet-stream"

                            val fileSize = call.request.header("X-File-Size")?.toLongOrNull() ?: -1L

                            val encryptionMode = call.request.header("X-Encryption-Mode") ?: "NONE"



                            if (fileSize > FileTransferManager.MAX_TRANSFER_BYTES) {

                                call.respondText("""{"error":"file exceeds 5GB limit"}""", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)

                                return@post

                            }



                            if (!clientSession.isHotspot && encryptionMode != "CHUNKED_AES_GCM") {

                                call.respondText("""{"error":"encryption required on shared Wi-Fi"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)

                                return@post

                            }



                            val destination = StoragePreferences.openDestinationStream(

                                applicationContext, fileName, mimeType

                            )

                            if (destination == null) {

                                call.respondText("""{"error":"cannot create destination file"}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)

                                return@post

                            }



                            val clientTransferId = call.request.header("X-Transfer-Id")?.takeIf { it.isNotBlank() }
                            val transferId = clientTransferId ?: UUID.randomUUID().toString()

                            transferRepo.addTransfer(

                                fileName = fileName,

                                fileSize = fileSize.coerceAtLeast(0L),

                                mimeType = mimeType,

                                direction = "received",

                                status = "in_progress",

                                localUriOrPath = destination.uri.toString(),

                                transferId = transferId

                            )

                            FileTransferManager.updateProgress(transferId, fileName, 0L, fileSize.coerceAtLeast(1L), "in_progress")

                            updateNotification("Receiving: $fileName...")



                            var lastNotifiedPct = -1

                            var lastNotifiedTimeMs = 0L



                            try {

                                val totalRead = withContext(Dispatchers.IO) {

                                    destination.outputStream.use { outStream ->

                                        if (encryptionMode == "CHUNKED_AES_GCM") {

                                            call.receiveChannel().toInputStream().use { netIn ->

                                                clientSession.crypto.decryptStreamChunked(netIn, outStream) { plainBytes ->

                                                    FileTransferManager.updateProgress(transferId, fileName, plainBytes, fileSize, "in_progress")

                                                    if (fileSize > 0L) {

                                                        val pct = ((plainBytes * 100L) / fileSize).toInt()

                                                        val now = System.currentTimeMillis()

                                                        if (pct != lastNotifiedPct && (now - lastNotifiedTimeMs >= 500L || pct == 100)) {

                                                            lastNotifiedPct = pct

                                                            lastNotifiedTimeMs = now

                                                            updateNotification("Receiving $fileName: $pct%")

                                                        }

                                                    }

                                                }

                                            }

                                        } else {

                                            call.receiveChannel().toInputStream().use { netIn ->

                                                val buffer = ByteArray(64 * 1024)

                                                var acc = 0L

                                                while (true) {

                                                    val read = netIn.read(buffer)

                                                    if (read <= 0) break

                                                    outStream.write(buffer, 0, read)

                                                    acc += read

                                                    FileTransferManager.updateProgress(transferId, fileName, acc, fileSize, "in_progress")

                                                    if (fileSize > 0L) {

                                                        val pct = ((acc * 100L) / fileSize).toInt()

                                                        val now = System.currentTimeMillis()

                                                        if (pct != lastNotifiedPct && (now - lastNotifiedTimeMs >= 500L || pct == 100)) {

                                                            lastNotifiedPct = pct

                                                            lastNotifiedTimeMs = now

                                                            updateNotification("Receiving $fileName: $pct%")

                                                        }

                                                    }

                                                }

                                                outStream.flush()

                                                acc

                                            }

                                        }

                                    }

                                }



                                if (fileSize > 0L && totalRead != fileSize) {

                                    throw IllegalStateException("Incomplete upload: expected $fileSize bytes, got $totalRead")

                                }



                                StoragePreferences.finalizeDestination(applicationContext, destination)

                                FileTransferManager.updateProgress(transferId, fileName, totalRead, totalRead, "completed")

                                transferRepo.updateTransferStatus(transferId, "completed", destination.uri.toString())



                                updateNotification("Received: $fileName ✓")

                                showFileReceivedNotification(fileName, destination.uri, mimeType)



                                call.respondText(

                                    gson.toJson(mapOf("status" to "received", "fileName" to fileName, "bytes" to totalRead)),

                                    ContentType.Application.Json

                                )

                            } catch (e: Exception) {

                                Log.e(TAG, "Error handling file upload", e)

                                StoragePreferences.deleteDestination(applicationContext, destination)

                                FileTransferManager.updateProgress(transferId, fileName, 0L, fileSize, "failed")

                                transferRepo.updateTransferStatus(transferId, "failed", null)

                                updateNotification("Receive failed: $fileName")

                                call.respondText(

                                    gson.toJson(mapOf("error" to "upload failed: ${e.message}")),

                                    ContentType.Application.Json,

                                    HttpStatusCode.InternalServerError

                                )

                            }

                        }



                        webSocket("/sync") {

                            val sessionId = UUID.randomUUID().toString()

                            val remoteIp = call.request.origin.remoteHost

                            val clientSession = ClientSession(

                                session = this,

                                remoteIp = remoteIp,

                                isHotspot = isHotspotIp(remoteIp)

                            )

                            connectedClients[sessionId] = clientSession

                            Log.i(TAG, "Windows client connected: $sessionId ($remoteIp, hotspot=${clientSession.isHotspot})")



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

                                if (!clientSession.isApproved && pendingConnectionRequest.value?.sessionId == sessionId) {

                                    pendingConnectionRequest.value?.onResult?.invoke(false)

                                    pendingConnectionRequest.value = null

                                }

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



    private suspend fun routeMessage(sessionId: String, rawJson: String) {

        val jsonObj = try {

            JsonParser.parseString(rawJson).asJsonObject

        } catch (e: Exception) {

            Log.w(TAG, "Non-JSON WebSocket message: $rawJson")

            return

        }



        val type = jsonObj.get("type")?.asString

        val clientSession = connectedClients[sessionId] ?: return



        when (type) {

            "key_exchange" -> {

                val remotePubKey = jsonObj.get("pubKey")?.asString ?: return

                val deviceId = jsonObj.get("deviceId")?.asString ?: "unknown"

                val deviceName = jsonObj.get("deviceName")?.asString ?: "Unknown PC"

                clientSession.deviceId = deviceId



                val isTrusted = trustedDeviceDao.countById(deviceId) > 0

                if (!isTrusted) {
                    // Decline any stale superseded request before replacing
                    pendingConnectionRequest.value?.onResult?.invoke(false)

                    val deferred = CompletableDeferred<Boolean>()

                    pendingConnectionRequest.value = ConnectionRequest(sessionId, deviceId, deviceName) { result ->

                        deferred.complete(result)

                        if (pendingConnectionRequest.value?.sessionId == sessionId) {

                            pendingConnectionRequest.value = null

                        }

                    }

                    val approved = withTimeoutOrNull(60_000L) { deferred.await() } ?: false

                    if (!approved) {

                        clientSession.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Connection rejected"))

                        return

                    }

                    trustedDeviceDao.insert(

                        TrustedDevice(

                            deviceId = deviceId,

                            deviceName = deviceName,

                            approvedAt = System.currentTimeMillis()

                        )

                    )

                }



                clientSession.crypto.generateKeyPair()

                clientSession.crypto.deriveSharedSecret(remotePubKey)

                clientSession.isApproved = true



                val response = gson.toJson(

                    mapOf(

                        "type" to "key_exchange",

                        "pubKey" to clientSession.crypto.getPublicKeyBase64(),

                        "sessionToken" to clientSession.sessionToken,

                        "networkMode" to if (clientSession.isHotspot) "HOTSPOT_DIRECT" else "SHARED_WIFI_LAN"

                    )

                )

                clientSession.session.send(Frame.Text(response))

                Log.i(TAG, "Key exchange completed with session $sessionId (hotspot=${clientSession.isHotspot})")

                updateNotification("🔒 Connected (${if (clientSession.isHotspot) "Hotspot" else "Wi-Fi E2EE"})")

            }



            "encrypted" -> {

                if (!clientSession.isApproved || !clientSession.crypto.isEstablished) {

                    Log.w(TAG, "Rejected encrypted frame on unauthenticated session")

                    return

                }

                val encryptedPayload = jsonObj.get("payload")?.asString ?: return

                val decrypted = clientSession.crypto.decrypt(encryptedPayload)

                routeMessage(sessionId, decrypted)

            }



            "clipboard" -> {

                if (!clientSession.isApproved) return

                val clipboardId = jsonObj.get("clipboardId")?.asString ?: return

                val source = jsonObj.get("source")?.asString ?: return

                val content = jsonObj.get("content")?.asString ?: return

                handleClipboardMessage(clipboardId, source, content)

            }



            "ping" -> {

                val pong = gson.toJson(mapOf("type" to "pong"))

                clientSession.session.send(Frame.Text(pong))

            }



            "files_available" -> Unit



            null -> {

                if (!clientSession.isApproved) return

                val payload = gson.fromJson(rawJson, ClipboardPayload::class.java)

                if (payload.clipboardId != null && payload.content != null && payload.source != null) {

                    handleClipboardMessage(payload.clipboardId, payload.source, payload.content)

                }

            }

        }

    }



    private suspend fun handleClipboardMessage(clipboardId: String, source: String, content: String) {
        val inserted = clipboardRepo.addItem(
            content = content,
            source = source,
            clipboardId = clipboardId
        )
        if (inserted) {
            markLocalClipboardWrite(content)
            withContext(Dispatchers.Main) {
                try {
                    val clip = ClipData.newPlainText("sync", content)
                    clipboardManager.setPrimaryClip(clip)
                } catch (e: Exception) {
                    Log.w(TAG, "Direct setPrimaryClip skipped by OS: ${e.message}")
                }
            }
            updateNotification("Received: ${content.take(30)}...")
            showClipboardReceivedNotification(content)
        }
    }

    private fun startClipboardListener() {
        clipboardManager.addPrimaryClipChangedListener {
            serviceScope.launch {
                try {
                    val clip = withContext(Dispatchers.Main) { clipboardManager.primaryClip }
                    val text = clip?.getItemAt(0)?.text?.toString() ?: return@launch

                    // Ignore clipboard changes that originated from Windows or our own Copy buttons
                    if (text == lastProgrammaticClip) {
                        return@launch
                    }

                    val newId = UUID.randomUUID().toString()
                    val inserted = clipboardRepo.addItem(
                        content = text,
                        source = "android",
                        clipboardId = newId
                    )

                    if (inserted) {
                        lastProgrammaticClip = text
                        val payload = ClipboardPayload(
                            clipboardId = newId,
                            source = "android",
                            content = text
                        )
                        sendEncryptedToAllClients(gson.toJson(payload))
                        updateNotification("Sent: ${text.take(30)}...")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in clipboard listener", e)
                }
            }
        }
    }

    private fun createNotificationChannels() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val manager = getSystemService(NotificationManager::class.java)



            val syncChannel = NotificationChannel(

                CHANNEL_ID,

                "Sync Agent Service",

                NotificationManager.IMPORTANCE_LOW

            ).apply {

                description = "Local Device Sync Agent background service"

            }

            manager.createNotificationChannel(syncChannel)



            val transferChannel = NotificationChannel(

                TRANSFER_CHANNEL_ID,

                "File Transfers",

                NotificationManager.IMPORTANCE_HIGH

            ).apply {

                description = "Notifications for received files"

            }

            manager.createNotificationChannel(transferChannel)



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



data class ClipboardPayload(

    val clipboardId: String? = null,

    val source: String? = null,

    val content: String? = null,

    val type: String = "clipboard"

)

