using System;

using System.Diagnostics;

using System.IO;

using System.Net;

using System.Net.Http;

using System.Threading;

using System.Threading.Tasks;



namespace WindowsAgent

{

    public class FileUploader

    {

        public const long MaxTransferBytes = 5L * 1024L * 1024L * 1024L; // 5 GB

        public const long WifiSafeEncryptedThreshold = 500L * 1024L * 1024L; // 500 MB



        private string? _hotspotIp = null;

        private int _signalingPort = 7777;

        private readonly ClipboardDatabase _db;

        private static readonly HttpClient _httpClient = new HttpClient { Timeout = TimeSpan.FromHours(4) };



        public event Action<string>? StatusChanged;

        public event Action<TransferEntry>? FileUploaded;



        public FileUploader(ClipboardDatabase db)

        {

            _db = db;

        }



        public void UpdateEndpoint(string ip, int port)

        {

            _hotspotIp = ip;

            _signalingPort = port;

        }



        public async Task<bool> UploadSingleFileAsync(
            string filePath,
            CancellationToken ct = default,
            Action<long, long>? onProgress = null,
            string? transferId = null)
        {
            var fileInfo = new FileInfo(filePath);
            if (!fileInfo.Exists || fileInfo.Length > MaxTransferBytes) return false;

            var fileName = fileInfo.Name;
            var fileSize = fileInfo.Length;
            var mimeType = GetMimeType(filePath);
            var resolvedTransferId = !string.IsNullOrEmpty(transferId) ? transferId : Guid.NewGuid().ToString();

            try
            {
                var targetIp = _hotspotIp ?? App.StorageSettings?.LastConnectedIp;
                if (string.IsNullOrEmpty(targetIp))
                {
                    StatusChanged?.Invoke("Upload failed: No Android device connected");
                    return false;
                }

                // Mode A (Hotspot): Raw stream; Mode B (Shared Wi-Fi): 64 KB Chunked AES-256-GCM
                bool encryptStream = !App.SyncEngine.IsHotspotMode;

                var url = $"http://{targetIp}:{_signalingPort}/upload";
                using var request = new HttpRequestMessage(HttpMethod.Post, url);

                request.Headers.Add("X-Transfer-Id", resolvedTransferId);
                request.Headers.Add("X-File-Name", Uri.EscapeDataString(fileName));

                request.Headers.Add("X-File-Size", fileSize.ToString());

                request.Headers.Add("X-Encryption-Mode", encryptStream ? "CHUNKED_AES_GCM" : "NONE");



                if (App.SyncEngine.SessionToken != null)

                {

                    request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", App.SyncEngine.SessionToken);

                }



                int lastReportedPercent = -1;

                using var fileStream = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, useAsync: true);

                using var content = new ProgressableStreamContent(

                    fileStream,

                    64 * 1024,

                    encryptStream ? App.SyncEngine.Crypto : null,

                    ct,

                    (sent, total) =>

                    {

                        onProgress?.Invoke(sent, total);

                        var percent = total > 0 ? (int)((sent * 100L) / total) : 0;

                        if (percent != lastReportedPercent)

                        {

                            lastReportedPercent = percent;

                            StatusChanged?.Invoke($"Sending {fileName}: {percent}%");

                        }

                    });



                content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue(mimeType);

                request.Content = content;



                using var response = await _httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);

                response.EnsureSuccessStatusCode();



                var entry = new TransferEntry

                {

                    TransferId = resolvedTransferId,

                    FileName = fileName,

                    FileSize = fileSize,

                    MimeType = mimeType,

                    Direction = "sent",

                    Status = "completed",

                    LocalPath = filePath,

                    Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()

                };



                _db.InsertTransfer(entry);

                FileUploaded?.Invoke(entry);

                StatusChanged?.Invoke($"Sent: {fileName} ✓");

                return true;

            }

            catch (Exception ex)

            {

                Debug.WriteLine($"FileUploader: Failed to upload {fileName}: {ex.Message}");

                StatusChanged?.Invoke($"Upload failed: {fileName}");

                return false;

            }

        }



        private static string GetMimeType(string path)

        {

            var ext = Path.GetExtension(path).ToLowerInvariant();

            return ext switch

            {

                ".jpg" or ".jpeg" => "image/jpeg",

                ".png" => "image/png",

                ".gif" => "image/gif",

                ".webp" => "image/webp",

                ".heic" => "image/heic",

                ".svg" => "image/svg+xml",

                ".mp4" => "video/mp4",

                ".mkv" => "video/x-matroska",

                ".mov" => "video/quicktime",

                ".avi" => "video/x-msvideo",

                ".webm" => "video/webm",

                ".mp3" => "audio/mpeg",

                ".wav" => "audio/wav",

                ".flac" => "audio/flac",

                ".aac" => "audio/aac",

                ".pdf" => "application/pdf",

                ".doc" => "application/msword",

                ".docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",

                ".xls" or ".xlsx" => "application/vnd.ms-excel",

                ".ppt" or ".pptx" => "application/vnd.ms-powerpoint",

                ".txt" => "text/plain",

                ".zip" => "application/zip",

                ".rar" => "application/x-rar-compressed",

                ".7z" => "application/x-7z-compressed",

                _ => "application/octet-stream"

            };

        }

    }



    public class ProgressableStreamContent : HttpContent

    {

        private readonly Stream _stream;

        private readonly int _bufferSize;

        private readonly SessionCrypto? _crypto;

        private readonly CancellationToken _ct;

        private readonly Action<long, long> _progressCallback;



        public ProgressableStreamContent(

            Stream stream,

            int bufferSize,

            SessionCrypto? crypto,

            CancellationToken ct,

            Action<long, long> progressCallback)

        {

            _stream = stream ?? throw new ArgumentNullException(nameof(stream));

            _bufferSize = bufferSize;

            _crypto = crypto;

            _ct = ct;

            _progressCallback = progressCallback ?? throw new ArgumentNullException(nameof(progressCallback));

        }



        protected override async Task SerializeToStreamAsync(Stream stream, TransportContext? context)

        {

            var totalLength = _stream.Length;



            if (_crypto != null)

            {

                await _crypto.EncryptStreamChunkedAsync(

                    _stream,

                    stream,

                    plainSent => _progressCallback(plainSent, totalLength),

                    _ct);

            }

            else

            {

                var buffer = new byte[_bufferSize];

                long totalRead = 0;

                int bytesRead;

                while ((bytesRead = await _stream.ReadAsync(buffer.AsMemory(0, buffer.Length), _ct)) > 0)

                {

                    await stream.WriteAsync(buffer.AsMemory(0, bytesRead), _ct);

                    totalRead += bytesRead;

                    _progressCallback(totalRead, totalLength);

                }

            }

        }



        protected override bool TryComputeLength(out long length)

        {

            if (_crypto != null)

            {

                length = 0;

                return false; // Chunked transfer encoding when AEAD framing adds IV + per-chunk tags

            }

            length = _stream.Length;

            return true;

        }



        protected override void Dispose(bool disposing)

        {

            if (disposing) _stream.Dispose();

            base.Dispose(disposing);

        }

    }

}

