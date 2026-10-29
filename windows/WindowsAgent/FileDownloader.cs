using System;

using System.Collections.Generic;

using System.Diagnostics;

using System.IO;

using System.Linq;

using System.Net.Http;

using System.Threading;

using System.Threading.Tasks;

using Newtonsoft.Json;



namespace WindowsAgent

{

    public class FileDownloader

    {

        private string? _hotspotIp = null;

        private int _signalingPort = 7777;

        private readonly StorageSettings _storageSettings;

        private readonly ClipboardDatabase _db;

        private static readonly HttpClient _client = new HttpClient

        {

            Timeout = TimeSpan.FromHours(4),

            MaxResponseContentBufferSize = 64 * 1024

        };

        private readonly HashSet<string> _enqueuedTokens = new();



        private string? TargetIp => _hotspotIp ?? _storageSettings?.LastConnectedIp;



        public event Action<string>? StatusChanged;

        public event Action<TransferEntry>? FileDownloaded;



        public FileDownloader(StorageSettings storageSettings, ClipboardDatabase db)

        {

            _storageSettings = storageSettings;

            _db = db;

        }



        public void UpdateEndpoint(string ip, int port)

        {

            _hotspotIp = ip;

            _signalingPort = port;

        }



        public async Task FetchAndEnqueuePendingTransfersAsync(TransferQueue queue)

        {

            if (string.IsNullOrEmpty(TargetIp) || string.IsNullOrEmpty(App.SyncEngine.SessionToken)) return;



            try

            {

                using var request = new HttpRequestMessage(HttpMethod.Get, $"http://{TargetIp}:{_signalingPort}/transfers");

                request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", App.SyncEngine.SessionToken);



                using var response = await _client.SendAsync(request);

                if (!response.IsSuccessStatusCode) return;



                var json = await response.Content.ReadAsStringAsync();

                var transfers = JsonConvert.DeserializeObject<List<TransferInfo>>(json);

                if (transfers == null || transfers.Count == 0) return;



                var newTransfers = new List<TransferInfo>();

                lock (_enqueuedTokens)

                {

                    foreach (var t in transfers)

                    {

                        if (_enqueuedTokens.Add(t.Token))

                            newTransfers.Add(t);

                    }

                }



                if (newTransfers.Count > 0)

                {

                    queue.EnqueueDownloads(newTransfers);

                }

            }

            catch (Exception ex)

            {

                Debug.WriteLine($"FileDownloader: Check failed: {ex.Message}");

            }

        }



        public async Task<bool> DownloadSingleFileAsync(

            TransferInfo info,

            Action<long, long>? onProgress = null,

            CancellationToken ct = default)

        {

            if (string.IsNullOrEmpty(TargetIp)) return false;



            string? tempFilePath = null;

            try

            {

                var downloadFolder = _storageSettings.DownloadFolder;

                Directory.CreateDirectory(downloadFolder);



                var invalidChars = Path.GetInvalidFileNameChars();

                var safeName = new string(info.FileName.Select(ch => invalidChars.Contains(ch) ? '_' : ch).ToArray());

                if (string.IsNullOrWhiteSpace(safeName)) safeName = $"transfer_{info.Token}";



                var finalFilePath = Path.Combine(downloadFolder, safeName);

                var counter = 1;

                var baseName = Path.GetFileNameWithoutExtension(safeName);

                var extension = Path.GetExtension(safeName);

                while (File.Exists(finalFilePath))

                {

                    finalFilePath = Path.Combine(downloadFolder, $"{baseName} ({counter}){extension}");

                    counter++;

                }



                tempFilePath = finalFilePath + ".part";



                using var request = new HttpRequestMessage(HttpMethod.Get, $"http://{TargetIp}:{_signalingPort}/transfer/{info.Token}");

                if (App.SyncEngine.SessionToken != null)

                {

                    request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", App.SyncEngine.SessionToken);

                }



                using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);

                response.EnsureSuccessStatusCode();



                var encryptionMode = response.Headers.TryGetValues("X-Encryption-Mode", out var modeVals)

                    ? modeVals.FirstOrDefault() ?? "NONE"

                    : "NONE";



                long totalRead = 0;

                await using (var networkStream = await response.Content.ReadAsStreamAsync(ct))

                await using (var fileStream = new FileStream(tempFilePath, FileMode.Create, FileAccess.Write, FileShare.None, 64 * 1024, useAsync: true))

                {

                    if (encryptionMode == "CHUNKED_AES_GCM")

                    {

                        totalRead = await App.SyncEngine.Crypto.DecryptStreamChunkedAsync(

                            networkStream,

                            fileStream,

                            bytesRead => onProgress?.Invoke(bytesRead, info.Size),

                            ct);

                    }

                    else

                    {

                        var buffer = new byte[64 * 1024];

                        int bytesRead;

                        while ((bytesRead = await networkStream.ReadAsync(buffer.AsMemory(0, buffer.Length), ct)) > 0)

                        {

                            await fileStream.WriteAsync(buffer.AsMemory(0, bytesRead), ct);

                            totalRead += bytesRead;

                            if (info.Size > 0)

                                onProgress?.Invoke(totalRead, info.Size);

                        }

                    }

                }



                if (info.Size > 0 && totalRead != info.Size)

                {

                    throw new IOException($"Incomplete transfer: expected {info.Size} bytes, got {totalRead} bytes.");

                }



                await MoveFileWithRetryAsync(tempFilePath, finalFilePath, ct);



                var entry = new TransferEntry

                {

                    TransferId = info.Token,

                    FileName = Path.GetFileName(finalFilePath),

                    FileSize = totalRead,

                    MimeType = info.MimeType,

                    Direction = "received",

                    Status = "completed",

                    LocalPath = finalFilePath,

                    Timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()

                };



                _db.InsertTransfer(entry);

                FileDownloaded?.Invoke(entry);

                StatusChanged?.Invoke($"Downloaded: {entry.FileName} ✓");



                await SendDeleteCompletionAsync(info.Token, "completed");

                return true;

            }

            catch (Exception ex)

            {

                if (tempFilePath != null && File.Exists(tempFilePath))

                {

                    try { File.Delete(tempFilePath); } catch { }

                }

                StatusChanged?.Invoke($"Download failed: {info.FileName}");

                Debug.WriteLine($"FileDownloader: Download error: {ex}");

                return false;

            }

        }



        public async Task NotifyTransferFailedAsync(string token)

        {

            lock (_enqueuedTokens) { _enqueuedTokens.Remove(token); }

            await SendDeleteCompletionAsync(token, "failed");

        }



        private async Task SendDeleteCompletionAsync(string token, string status)

        {

            if (string.IsNullOrEmpty(TargetIp)) return;

            try

            {

                using var deleteReq = new HttpRequestMessage(HttpMethod.Delete, $"http://{TargetIp}:{_signalingPort}/transfer/{token}?status={status}");

                if (!string.IsNullOrEmpty(App.SyncEngine.SessionToken))

                    deleteReq.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", App.SyncEngine.SessionToken);

                _ = await _client.SendAsync(deleteReq);

            }

            catch (Exception delEx)

            {

                Debug.WriteLine($"FileDownloader: Ack failed: {delEx.Message}");

            }

        }

    

        /// <summary>
        /// Windows Defender Real-Time Protection frequently scans newly closed .part files for 50-300ms.
        /// Retrying File.Move prevents a completed multi-GB download from failing with ERROR_SHARING_VIOLATION.
        /// </summary>
        private static async Task MoveFileWithRetryAsync(string sourcePath, string destPath, CancellationToken ct)
        {
            const int maxAttempts = 8;
            for (int attempt = 1; attempt <= maxAttempts; attempt++)
            {
                try
                {
                    File.Move(sourcePath, destPath, overwrite: true);
                    return;
                }
                catch (IOException) when (attempt < maxAttempts)
                {
                    await Task.Delay(150 * attempt, ct);
                }
            }
        }
}



    public class TransferInfo

    {

        [JsonProperty("token")]

        public string Token { get; set; } = "";



        [JsonProperty("fileName")]

        public string FileName { get; set; } = "";



        [JsonProperty("mimeType")]

        public string MimeType { get; set; } = "application/octet-stream";



        [JsonProperty("size")]

        public long Size { get; set; }


    }

}