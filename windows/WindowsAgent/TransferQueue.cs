using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;

namespace WindowsAgent
{
    public enum TransferStatus
    {
        Pending,
        InProgress,
        Done,
        Failed,
        Retrying
    }

    public class TransferQueueItem : INotifyPropertyChanged
    {
        public string Id { get; } = Guid.NewGuid().ToString();
        public string FileName { get; init; } = "";
        public string FilePath { get; init; } = "";
        public string Direction { get; init; } = "Upload";
        public string MimeType { get; init; } = "";

        public string? DownloadToken { get; init; }
        public long TotalBytes { get; init; }
        public CancellationTokenSource Cancellation { get; } = new();
        public DateTime? CompletedAtUtc { get; set; }
        public long LastUiUpdateTick { get; set; }

        private TransferStatus _status = TransferStatus.Pending;
        public TransferStatus Status
        {
            get => _status;
            set
            {
                if (_status == value) return;
                _status = value;
                OnPropertyChanged(nameof(Status));
                OnPropertyChanged(nameof(StatusDisplay));
                OnPropertyChanged(nameof(IsActive));
                OnPropertyChanged(nameof(ProgressText));
            }
        }

        private int _progress;
        public int Progress
        {
            get => _progress;
            set
            {
                if (_progress == value) return;
                _progress = value;
                OnPropertyChanged(nameof(Progress));
                OnPropertyChanged(nameof(StatusDisplay));
            }
        }

        private long _transferredBytes;
        public long TransferredBytes
        {
            get => _transferredBytes;
            set
            {
                if (_transferredBytes == value) return;
                _transferredBytes = value;
                OnPropertyChanged(nameof(TransferredBytes));
                OnPropertyChanged(nameof(ProgressText));
            }
        }

        public int RetryCount { get; set; }

        public string DirectionIcon => Direction == "Upload" ? "↑" : "↓";
        public string StatusDisplay => Status switch
        {
            TransferStatus.Pending    => "Queued",
            TransferStatus.InProgress => $"{Progress}%",
            TransferStatus.Done       => "✓ Done",
            TransferStatus.Failed     => "✗ Failed",
            TransferStatus.Retrying   => $"Retry {RetryCount}…",
            _                         => ""
        };
        public string ProgressText => Status is TransferStatus.InProgress or TransferStatus.Pending
            ? $"{FormatBytes(TransferredBytes)} / {FormatBytes(TotalBytes)}"
            : "";

        public bool IsActive => Status is TransferStatus.Pending or TransferStatus.InProgress or TransferStatus.Retrying;

        public event PropertyChangedEventHandler? PropertyChanged;
        protected void OnPropertyChanged(string name)
        {
            var dispatcher = Application.Current?.Dispatcher;
            if (dispatcher == null || dispatcher.HasShutdownStarted) return;
            if (dispatcher.CheckAccess())
            {
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
            }
            else
            {
                _ = dispatcher.BeginInvoke(
                    DispatcherPriority.DataBind,
                    new Action(() => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name))));
            }
        }

        private static string FormatBytes(long bytes)
        {
            if (bytes <= 0) return "0 B";
            string[] units = { "B", "KB", "MB", "GB", "TB" };
            int i = (int)(Math.Log10(Math.Max(bytes, 1)) / Math.Log10(1024));
            i = Math.Clamp(i, 0, units.Length - 1);
            return $"{bytes / Math.Pow(1024, i):F1} {units[i]}";
        }
    }

    public class TransferQueue
    {
        private const int MaxRetries = 3;
        private static readonly TimeSpan RetryDelay = TimeSpan.FromSeconds(2);

        public ObservableCollection<TransferQueueItem> Items { get; } = new();

        public event Action<string>? StatusChanged;
        public event Action<bool>? ActiveStateChanged;

        private readonly SemaphoreSlim _semaphore = new(1, 1);

        private void NotifyActiveState()
        {
            var dispatcher = Application.Current?.Dispatcher;
            if (dispatcher == null || dispatcher.HasShutdownStarted) return;

            _ = dispatcher.BeginInvoke(() =>
            {
                var cutoff = DateTime.UtcNow.AddSeconds(-3.8);
                var toPrune = Items.Where(i => !i.IsActive && i.CompletedAtUtc.HasValue && i.CompletedAtUtc.Value <= cutoff).ToList();
                foreach (var old in toPrune)
                {
                    Items.Remove(old);
                }
                ActiveStateChanged?.Invoke(Items.Count > 0);
            });
        }

        /// <summary>Enqueue one or more file or folder uploads without blocking the UI thread on directory I/O.</summary>
        public void EnqueueUploads(IEnumerable<string> paths)
        {
            var pathList = paths.ToList();
            _ = Task.Run(() => ExpandAndEnqueueUploadsAsync(pathList));
        }

        private async Task ExpandAndEnqueueUploadsAsync(List<string> paths)
        {
            var expandedFiles = new List<(string Path, string Name, long Length)>();
            long totalBatchBytes = 0;

            foreach (var p in paths)
            {
                if (File.Exists(p))
                {
                    var fi = new FileInfo(p);
                    expandedFiles.Add((p, fi.Name, fi.Length));
                    totalBatchBytes += fi.Length;
                }
                else if (Directory.Exists(p))
                {
                    try
                    {
                        foreach (var f in Directory.EnumerateFiles(p, "*", SearchOption.AllDirectories))
                        {
                            var fi = new FileInfo(f);
                            expandedFiles.Add((f, fi.Name, fi.Length));
                            totalBatchBytes += fi.Length;
                        }
                    }
                    catch { }
                }
            }

            if (expandedFiles.Count == 0) return;

            var oversize = expandedFiles.FirstOrDefault(f => f.Length > FileUploader.MaxTransferBytes);
            if (oversize.Path != null)
            {
                Application.Current?.Dispatcher.Invoke(() =>
                {
                    MessageBox.Show(
                        $"'{oversize.Name}' exceeds the 5 GB maximum transfer limit.",
                        "DeviceSync — File Too Large",
                        MessageBoxButton.OK,
                        MessageBoxImage.Warning);
                });
                return;
            }

            if (!App.SyncEngine.IsHotspotMode && totalBatchBytes > FileUploader.WifiSafeEncryptedThreshold)
            {
                bool proceed = false;
                Application.Current?.Dispatcher.Invoke(() =>
                {
                    var confirm = MessageBox.Show(
                        $"You are sending {totalBatchBytes / (1024 * 1024)} MB over a shared Wi-Fi network, which requires chunked AES-256-GCM encryption.\n\nSwitch to Phone Hotspot Mode for maximum speed, or click OK to send encrypted over Wi-Fi.",
                        "DeviceSync — Large Wi-Fi Transfer",
                        MessageBoxButton.OKCancel,
                        MessageBoxImage.Information);
                    proceed = confirm == MessageBoxResult.OK;
                });
                if (!proceed) return;
            }

            var items = new List<TransferQueueItem>();
            Application.Current?.Dispatcher.Invoke(() =>
            {
                foreach (var file in expandedFiles)
                {
                    var item = new TransferQueueItem
                    {
                        FileName   = file.Name,
                        FilePath   = file.Path,
                        Direction  = "Upload",
                        MimeType   = "application/octet-stream",
                        TotalBytes = file.Length
                    };
                    Items.Add(item);
                    items.Add(item);
                }
            });

            NotifyActiveState();
            await ProcessItemsAsync(items);
        }

        public void EnqueueDownloads(IEnumerable<TransferInfo> transfers)
        {
            var items = new List<TransferQueueItem>();
            Application.Current?.Dispatcher.Invoke(() =>
            {
                foreach (var t in transfers)
                {
                    var item = new TransferQueueItem
                    {
                        FileName      = t.FileName,
                        Direction     = "Download",
                        MimeType      = t.MimeType,
                        DownloadToken = t.Token,
                        TotalBytes    = t.Size
                    };
                    Items.Add(item);
                    items.Add(item);
                }
            });
            NotifyActiveState();
            _ = ProcessItemsAsync(items);
        }

        public void Cancel(string itemId)
        {
            var item = Items.FirstOrDefault(i => i.Id == itemId);
            if (item != null && item.Status != TransferStatus.Done)
            {
                var wasPendingDownload = item.Status == TransferStatus.Pending &&
                                         item.Direction == "Download" &&
                                         item.DownloadToken != null;

                item.Status = TransferStatus.Failed;
                item.CompletedAtUtc = DateTime.UtcNow;
                item.Cancellation.Cancel();

                if (wasPendingDownload && item.DownloadToken != null)
                {
                    _ = App.FileDownloader.NotifyTransferFailedAsync(item.DownloadToken);
                }

                NotifyActiveState();
                _ = Task.Delay(4000).ContinueWith(_ => NotifyActiveState());
            }
        }

        private async Task ProcessItemsAsync(IEnumerable<TransferQueueItem> items)
        {
            foreach (var item in items)
            {
                if (item.Cancellation.IsCancellationRequested) continue;

                await _semaphore.WaitAsync();
                try
                {
                    await RunWithRetryAsync(item);
                }
                finally
                {
                    _semaphore.Release();
                    NotifyActiveState();
                }
            }
        }

        private async Task RunWithRetryAsync(TransferQueueItem item)
        {
            for (int attempt = 1; attempt <= MaxRetries; attempt++)
            {
                if (item.Cancellation.IsCancellationRequested) return;

                item.Status = attempt == 1 ? TransferStatus.InProgress : TransferStatus.Retrying;
                item.RetryCount = attempt - 1;

                bool success = item.Direction == "Upload"
                    ? await TryUploadAsync(item)
                    : await TryDownloadAsync(item);

                if (success)
                {
                    item.TransferredBytes = item.TotalBytes;
                    item.Progress = 100;
                    item.Status = TransferStatus.Done;
                    item.CompletedAtUtc = DateTime.UtcNow;
                    StatusChanged?.Invoke($"✓ {item.Direction}: {item.FileName}");
                    _ = Task.Delay(4000).ContinueWith(_ => NotifyActiveState());
                    return;
                }

                if (item.Cancellation.IsCancellationRequested) break;

                if (attempt < MaxRetries)
                {
                    StatusChanged?.Invoke($"Retry {attempt}/{MaxRetries}: {item.FileName}");
                    try
                    {
                        await Task.Delay(RetryDelay, item.Cancellation.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                }
            }

            item.Status = TransferStatus.Failed;
            item.CompletedAtUtc = DateTime.UtcNow;
            if (item.Direction == "Download" && item.DownloadToken != null)
            {
                await App.FileDownloader.NotifyTransferFailedAsync(item.DownloadToken);
            }
            StatusChanged?.Invoke($"Failed: {item.FileName}");
            _ = Task.Delay(4000).ContinueWith(_ => NotifyActiveState());
        }

        private static void UpdateItemProgressThrottled(TransferQueueItem item, long transferred, long total)
        {
            int newPct = total > 0 ? (int)((transferred * 100L) / total) : 0;
            long nowTicks = Environment.TickCount64;
            if (newPct != item.Progress || transferred >= total || nowTicks - item.LastUiUpdateTick >= 80)
            {
                item.LastUiUpdateTick = nowTicks;
                item.TransferredBytes = transferred;
                item.Progress = newPct;
            }
        }

        private Task<bool> TryUploadAsync(TransferQueueItem item)
        {
            return App.FileUploader.UploadSingleFileAsync(
                item.FilePath,
                item.Cancellation.Token,
                onProgress: (sent, total) => UpdateItemProgressThrottled(item, sent, total),
                transferId: item.Id);
        }

        private Task<bool> TryDownloadAsync(TransferQueueItem item)
        {
            if (item.DownloadToken == null) return Task.FromResult(false);
            return App.FileDownloader.DownloadSingleFileAsync(
                new TransferInfo
                {
                    Token = item.DownloadToken,
                    FileName = item.FileName,
                    MimeType = item.MimeType,
                    Size = item.TotalBytes
                },
                onProgress: (received, total) => UpdateItemProgressThrottled(item, received, total),
                ct: item.Cancellation.Token);
        }
    }
}
