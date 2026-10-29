using System;

using System.Diagnostics;

using System.IO;

using System.Net.WebSockets;

using System.Text;

using System.Threading;

using System.Threading.Tasks;

using System.Windows;

using Newtonsoft.Json;

using Newtonsoft.Json.Linq;



namespace WindowsAgent

{

    public class ClipboardSyncEngine

    {

        private string? _hotspotIp = null;

        private int _signalingPort = 7777;



        private readonly ClipboardDatabase _db;

        private readonly SessionCrypto _crypto = new();

        private ClientWebSocket? _webSocket;

        private CancellationTokenSource? _wsCancellation;

        private Timer? _heartbeatTimer;

        private readonly SemaphoreSlim _sendLock = new(1, 1);
        private long _lastReceiveTick;

        private void ForceCleanupDeadSocket()
        {
            _heartbeatTimer?.Dispose();
            _heartbeatTimer = null;
            SessionToken = null;
            _crypto.Reset();
            try { _webSocket?.Abort(); } catch { }
            try { _webSocket?.Dispose(); } catch { }
            _webSocket = null;
        }

        public string? ConnectedIp => _hotspotIp ?? App.StorageSettings?.LastConnectedIp;



        public void UpdateEndpoint(string ip, int port)

        {

            _hotspotIp = ip;

            _signalingPort = port;

        }



        private string? _lastClipboardContent;

        private string? _pendingOutboundClipboard = null;



        public event Action<string>? StatusChanged;

        public event Action<string>? ClipboardReceived;

        public event Action? FilesAvailableReceived;



        public bool IsEncrypted => _crypto.IsEstablished;

        public SessionCrypto Crypto => _crypto;

        public string? SessionToken { get; private set; }

        public bool WasRejectedByPeer { get; private set; }



        public void ResetRejectionState()

        {

            WasRejectedByPeer = false;

        }



        /// <summary>

        /// True when connected over Phone Hotspot / USB Tethering (Mode A), false on Shared Wi-Fi LAN (Mode B).

        /// </summary>

        public bool IsHotspotMode { get; private set; } = true;



        public bool IsConnected => _webSocket?.State == WebSocketState.Open;



        public ClipboardSyncEngine(ClipboardDatabase db)

        {

            _db = db;

        }



        public async Task ConnectAsync()

        {

            if (_webSocket?.State == WebSocketState.Open) return;



            try

            {

                _heartbeatTimer?.Dispose();

                _heartbeatTimer = null;

                _wsCancellation?.Cancel();

                _webSocket?.Dispose();



                var targetIp = _hotspotIp ?? App.StorageSettings?.LastConnectedIp;

                if (string.IsNullOrEmpty(targetIp))

                {

                    Debug.WriteLine("ClipboardSyncEngine: ConnectAsync aborted - no IP specified.");

                    return;

                }



                _wsCancellation = new CancellationTokenSource();

                _webSocket = new ClientWebSocket();



                var uri = new Uri($"ws://{targetIp}:{_signalingPort}/sync");

                StatusChanged?.Invoke("Connecting to Android agent...");



                await _webSocket.ConnectAsync(uri, _wsCancellation.Token);
                _lastReceiveTick = Environment.TickCount64;

                StatusChanged?.Invoke("Connected ✓");



                await InitiateKeyExchangeAsync();



                _ = Task.Run(() => ReceiveLoopAsync(_wsCancellation.Token));



                _heartbeatTimer = new Timer(

                    _ => _ = SendHeartbeatAsync(),

                    null,

                    TimeSpan.FromSeconds(30),

                    TimeSpan.FromSeconds(30));

            }

            catch (Exception ex)

            {

                StatusChanged?.Invoke($"Connection failed: {ex.Message}");

                Debug.WriteLine($"WebSocket connection failed: {ex}");

            }

        }



        public async Task DisconnectAsync()

        {

            _heartbeatTimer?.Dispose();

            _heartbeatTimer = null;

            _wsCancellation?.Cancel();



            if (_webSocket?.State == WebSocketState.Open)

            {

                try

                {

                    await _webSocket.CloseAsync(

                        WebSocketCloseStatus.NormalClosure,

                        "Client disconnecting",

                        CancellationToken.None);

                }

                catch { }

            }

            _webSocket?.Dispose();

            _webSocket = null;

            SessionToken = null;

            _crypto.Reset();

            StatusChanged?.Invoke("Disconnected");

        }



        public async Task OnWindowsClipboardChangedAsync(string text)

        {

            if (text == _lastClipboardContent) return;

            await SendClipboardTextAsync(text);

        }



        public async Task<bool> SendClipboardTextAsync(string text)

        {

            if (string.IsNullOrEmpty(text)) return false;



            _lastClipboardContent = text;

            var clipboardId = Guid.NewGuid().ToString();



            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            _db.InsertItem(clipboardId, "windows", text, timestamp);



            if (_webSocket?.State == WebSocketState.Open && _crypto.IsEstablished)

            {

                var innerPayload = new

                {

                    type = "clipboard",

                    clipboardId,

                    source = "windows",

                    content = text

                };

                var innerJson = JsonConvert.SerializeObject(innerPayload);

                var encryptedPayload = _crypto.Encrypt(innerJson);

                var envelopeJson = JsonConvert.SerializeObject(new

                {

                    type = "encrypted",

                    payload = encryptedPayload

                });

                try

                {

                    await SendRawAsync(envelopeJson, throwOnError: true);

                    StatusChanged?.Invoke($"Sent: {text[..Math.Min(text.Length, 30)]}...");

                    _pendingOutboundClipboard = null;

                    return true;

                }

                catch (Exception ex)

                {

                    Debug.WriteLine($"Error sending clipboard: {ex}");

                    StatusChanged?.Invoke("Send failed, will retry on reconnect...");

                    _lastClipboardContent = null;

                    _pendingOutboundClipboard = text;

                    return false;

                }

            }

            else

            {

                _pendingOutboundClipboard = text;

                _lastClipboardContent = null;

                StatusChanged?.Invoke($"Queued for send ({text[..Math.Min(text.Length, 20)]}...)");

                return false;

            }

        }



        private async Task ReceiveLoopAsync(CancellationToken ct)
        {
            var buffer = new byte[8192];

            try
            {
                while (!ct.IsCancellationRequested && _webSocket?.State == WebSocketState.Open)
                {
                    using var messageBuilder = new MemoryStream();
                    WebSocketReceiveResult result;

                    do
                    {
                        result = await _webSocket.ReceiveAsync(
                            new ArraySegment<byte>(buffer), ct);

                        _lastReceiveTick = Environment.TickCount64;

                        if (result.MessageType == WebSocketMessageType.Close)
                        {
                            if (result.CloseStatus == WebSocketCloseStatus.PolicyViolation)
                            {
                                WasRejectedByPeer = true;
                                StatusChanged?.Invoke("Connection declined or revoked on phone");
                            }
                            else
                            {
                                StatusChanged?.Invoke("Android disconnected");
                            }
                            return;
                        }

                        messageBuilder.Write(buffer, 0, result.Count);
                    }
                    while (!result.EndOfMessage);

                    if (result.MessageType == WebSocketMessageType.Text)
                    {
                        var json = Encoding.UTF8.GetString(
                            messageBuilder.GetBuffer(), 0, (int)messageBuilder.Length);
                        RouteIncomingMessage(json);
                    }
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"WebSocket receive error: {ex}");
                StatusChanged?.Invoke("Connection lost, will retry...");
            }
            finally
            {
                // Ensure _heartbeatTimer and _webSocket are always disposed when the receive loop exits (fixes BUG-03)
                ForceCleanupDeadSocket();
            }
        }

        private async Task InitiateKeyExchangeAsync()

        {

            try

            {

                _crypto.GenerateKeyPair();

                var payload = new

                {

                    type = "key_exchange",

                    pubKey = _crypto.GetPublicKeyBase64(),

                    deviceId = App.StorageSettings.DeviceId,

                    deviceName = Environment.MachineName

                };

                var json = JsonConvert.SerializeObject(payload);

                await SendRawAsync(json);



                StatusChanged?.Invoke("🔑 Key exchange initiated...");

            }

            catch (Exception ex)

            {

                Debug.WriteLine($"Key exchange initiation failed: {ex}");

            }

        }



        private void RouteIncomingMessage(string json)

        {

            try

            {

                JObject? obj;

                try

                {

                    obj = JObject.Parse(json);

                }

                catch

                {

                    return;

                }



                var type = obj["type"]?.ToString();



                switch (type)

                {

                    case "key_exchange":

                        var remotePubKey = obj["pubKey"]?.ToString();

                        var token = obj["sessionToken"]?.ToString();

                        var netMode = obj["networkMode"]?.ToString();

                        if (remotePubKey != null)

                        {

                            _crypto.DeriveSharedSecret(remotePubKey);

                            SessionToken = token;

                            IsHotspotMode = netMode != "SHARED_WIFI_LAN";

                            StatusChanged?.Invoke($"🔒 Encrypted session ({(IsHotspotMode ? "Hotspot" : "Shared Wi-Fi")})");



                            // Flush any queued clipboard item now that encryption is ready

                            if (!string.IsNullOrEmpty(_pendingOutboundClipboard))

                            {

                                var queuedText = _pendingOutboundClipboard;

                                _ = Task.Run(() => SendClipboardTextAsync(queuedText));

                            }



                            // Check for any files staged on Android prior to key exchange (fixes BUG-09)

                            FilesAvailableReceived?.Invoke();

                        }

                        break;



                    case "encrypted":

                        var encPayload = obj["payload"]?.ToString();

                        if (encPayload != null && _crypto.IsEstablished)

                        {

                            var decrypted = _crypto.Decrypt(encPayload);

                            RouteIncomingMessage(decrypted);

                        }

                        break;



                    case "clipboard":

                    case null:

                        if (obj["content"] != null || obj["clipboardId"] != null)

                        {

                            HandleClipboardPayload(obj);

                        }

                        break;



                    case "files_available":

                        FilesAvailableReceived?.Invoke();

                        break;



                    case "pong":

                        break;

                }

            }

            catch (Exception ex)

            {

                Debug.WriteLine($"Error routing incoming message: {ex}");

            }

        }



        private void HandleClipboardPayload(JObject obj)

        {

            var content = obj["content"]?.ToString();

            if (content == null) return;



            var clipboardId = obj["clipboardId"]?.ToString() ?? Guid.NewGuid().ToString();

            var source = obj["source"]?.ToString() ?? "android";



            if (source == "windows") return;

            if (_db.ExistsById(clipboardId)) return;



            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

            _db.InsertItem(clipboardId, source, content, timestamp);



            _lastClipboardContent = content;



            Application.Current?.Dispatcher.Invoke(() =>

            {

                SetClipboardWithRetry(content);

                ClipboardReceived?.Invoke(content);

                StatusChanged?.Invoke($"Received: {content[..Math.Min(content.Length, 30)]}...");

            });

        }



        private void SetClipboardWithRetry(string text, int maxRetries = 3)

        {

            for (int i = 0; i < maxRetries; i++)

            {

                try

                {

                    if (string.IsNullOrEmpty(text))

                        Clipboard.Clear();

                    else

                        Clipboard.SetText(text);

                    return;

                }

                catch (Exception ex)

                {

                    Debug.WriteLine($"Clipboard.SetText attempt {i + 1} failed: {ex.Message}");

                    if (i < maxRetries - 1)

                        Thread.Sleep(100);

                }

            }

        }



        private async Task SendHeartbeatAsync()
        {
            if (_webSocket?.State != WebSocketState.Open) return;

            // If no frame/pong has arrived for >75s (2.5 heartbeat intervals), the TCP socket is half-open dead
            if (Environment.TickCount64 - Interlocked.Read(ref _lastReceiveTick) > 75_000)
            {
                Debug.WriteLine("WebSocket heartbeat timeout (>75s without pong) — resetting socket.");
                ForceCleanupDeadSocket();
                StatusChanged?.Invoke("Connection timed out, reconnecting...");
                return;
            }

            try
            {
                var ping = JsonConvert.SerializeObject(new { type = "ping" });
                await SendRawAsync(ping, throwOnError: true);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Heartbeat send failed, resetting dead socket: {ex.Message}");
                ForceCleanupDeadSocket();
                StatusChanged?.Invoke("Connection lost, reconnecting...");
            }
        }

        public async Task SendRawAsync(string json, bool throwOnError = false)

        {

            var ws = _webSocket;

            if (ws?.State != WebSocketState.Open) return;



            var bytes = Encoding.UTF8.GetBytes(json);

            await _sendLock.WaitAsync();

            try

            {

                if (ws.State == WebSocketState.Open)

                {

                    await ws.SendAsync(

                        new ArraySegment<byte>(bytes),

                        WebSocketMessageType.Text,

                        true,

                        _wsCancellation?.Token ?? CancellationToken.None);

                }

            }

            catch (Exception ex)

            {

                Debug.WriteLine($"SendRawAsync error: {ex}");

                if (throwOnError) throw;

            }

            finally

            {

                _sendLock.Release();

            }

        }

    }

}

