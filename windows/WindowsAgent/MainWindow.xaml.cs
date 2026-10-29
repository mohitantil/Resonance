using System;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;

namespace WindowsAgent;

/// <summary>
/// Interaction logic for MainWindow.xaml
/// </summary>
public partial class MainWindow : Window
{
    private readonly ClipboardListener _clipboardListener;
    private readonly ObservableCollection<ClipboardDisplayItem> _clipboardItems = new();
    private readonly ObservableCollection<TransferDisplayItem> _transferItems = new();

        private static readonly SolidColorBrush BrushGreen  = CreateFrozenBrush(0xA6, 0xE3, 0xA1);
    private static readonly SolidColorBrush BrushYellow = CreateFrozenBrush(0xF9, 0xE2, 0xAF);
    private static readonly SolidColorBrush BrushRed    = CreateFrozenBrush(0xF3, 0x8B, 0xA8);
    private static readonly SolidColorBrush BrushBlue   = CreateFrozenBrush(0x89, 0xB4, 0xFA);
    private static readonly SolidColorBrush BrushMuted  = CreateFrozenBrush(0x6C, 0x70, 0x86);

    private static SolidColorBrush CreateFrozenBrush(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
    }

    public MainWindow()
    {
        InitializeComponent();

        _clipboardListener = new ClipboardListener();
        ClipboardList.ItemsSource = _clipboardItems;
        TransfersList.ItemsSource = _transferItems;

        // Bind active transfers panel to the shared queue
        ActiveTransfersList.ItemsSource = App.TransferQueue.Items;
        App.TransferQueue.ActiveStateChanged += hasActive =>
            Dispatcher.Invoke(() =>
                ActiveTransfersPanel.Visibility = hasActive ? Visibility.Visible : Visibility.Collapsed);
        App.TransferQueue.StatusChanged += OnStatusChanged;

        DownloadPathText.Text = App.StorageSettings.DownloadFolder;

        // Wire up clipboard events
        App.SyncEngine.StatusChanged += OnStatusChanged;
        App.Monitor.StatusChanged += OnStatusChanged;
        App.SyncEngine.ClipboardReceived += OnClipboardReceived;

        // Wire up file transfer events (for status bar + history refresh)
        App.FileDownloader.StatusChanged += OnStatusChanged;
        App.FileDownloader.FileDownloaded += OnFileTransferCompleted;

        App.FileUploader.StatusChanged += OnStatusChanged;
        App.FileUploader.FileUploaded += OnFileTransferCompleted;

        App.StorageSettings.DownloadPathChanged += path => Dispatcher.Invoke(() => DownloadPathText.Text = path);

        Loaded += OnWindowLoaded;
        Closed += OnWindowClosed;
    }

    private void OnWindowLoaded(object sender, RoutedEventArgs e)
    {
        // Start native clipboard listener
        _clipboardListener.Start(this);
        _clipboardListener.ClipboardTextChanged += OnLocalClipboardChanged;

        // Update SendTo button status
        if (ShellIntegration.IsSendToRegistered())
        {
            SendToMenuBtn.Content = "✓ Added to Right-Click";
            SendToMenuBtn.IsEnabled = false;
        }

        RefreshHistory();
        RefreshTransfers();
    }

    private void OnWindowClosed(object? sender, EventArgs e)
    {
        _clipboardListener.ClipboardTextChanged -= OnLocalClipboardChanged;
        _clipboardListener.Dispose();
    }

    // ─── Clipboard handlers ─────────────────────────────────────────────

    private async void OnLocalClipboardChanged(string text)
    {
        await App.SyncEngine.OnWindowsClipboardChangedAsync(text);
        Dispatcher.Invoke(RefreshHistory);
    }

    private void OnClipboardReceived(string content)
    {
        Dispatcher.Invoke(RefreshHistory);
    }

    private async void OnSendCurrentClipboardClicked(object sender, RoutedEventArgs e)
    {
        try
        {
            if (Clipboard.ContainsText())
            {
                var text = Clipboard.GetText();
                if (!string.IsNullOrEmpty(text))
                {
                    await App.SyncEngine.SendClipboardTextAsync(text);
                    RefreshHistory();
                }
            }
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Failed to read current clipboard: {ex.Message}");
        }
    }

    private async void OnManualClipboardSendClicked(object sender, RoutedEventArgs e)
    {
        var text = ManualClipboardInput.Text.Trim();
        if (string.IsNullOrEmpty(text)) return;

        await App.SyncEngine.SendClipboardTextAsync(text);
        ManualClipboardInput.Clear();
        RefreshHistory();
    }

    private void OnManualClipboardInputKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            OnManualClipboardSendClicked(sender, e);
        }
    }

    private async void OnClipboardItemDoubleClicked(object sender, MouseButtonEventArgs e)
    {
        if (ClipboardList.SelectedItem is ClipboardDisplayItem item && !string.IsNullOrEmpty(item.Content))
        {
            await App.SyncEngine.SendClipboardTextAsync(item.Content);
            RefreshHistory();
        }
    }

    // ─── File Transfer UI handlers ──────────────────────────────────────

    private void OnTabClipboardClicked(object sender, RoutedEventArgs e)
    {
        ClipboardView.Visibility = Visibility.Visible;
        TransfersView.Visibility = Visibility.Collapsed;
        TabClipboardBtn.Background = new SolidColorBrush(Color.FromRgb(0x45, 0x47, 0x5A));
        TabTransfersBtn.Background = new SolidColorBrush(Color.FromRgb(0x31, 0x32, 0x44));
    }

    private void OnTabTransfersClicked(object sender, RoutedEventArgs e)
    {
        ClipboardView.Visibility = Visibility.Collapsed;
        TransfersView.Visibility = Visibility.Visible;
        TabClipboardBtn.Background = new SolidColorBrush(Color.FromRgb(0x31, 0x32, 0x44));
        TabTransfersBtn.Background = new SolidColorBrush(Color.FromRgb(0x45, 0x47, 0x5A));
        RefreshTransfers();
    }

    private void OnSelectFilesClicked(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "Select Photos, Videos, or Documents to Send",
            Multiselect = true,
            Filter = "All Files (*.*)|*.*|Images|*.jpg;*.jpeg;*.png;*.webp;*.heic|Videos|*.mp4;*.mkv;*.mov;*.avi|Documents|*.pdf;*.docx;*.xlsx;*.pptx;*.txt;*.zip"
        };

        if (dialog.ShowDialog(this) == true && dialog.FileNames.Length > 0)
        {
            App.TransferQueue.EnqueueUploads(dialog.FileNames);
        }
    }

    private void OnChangeDownloadFolderClicked(object sender, RoutedEventArgs e)
    {
        App.StorageSettings.BrowseAndSelectFolder(this);
    }

    private void OnOpenDownloadFolderClicked(object sender, RoutedEventArgs e)
    {
        App.StorageSettings.OpenInExplorer();
    }

    private void OnRegisterSendToClicked(object sender, RoutedEventArgs e)
    {
        bool success = ShellIntegration.RegisterSendToShortcut();
        if (success)
        {
            SendToMenuBtn.Content = "✓ Added to Right-Click";
            SendToMenuBtn.IsEnabled = false;
            MessageBox.Show(
                "You can now right-click any file in Windows File Explorer -> 'Send to' -> 'Send to Android (DeviceSync)' to beam it instantly!",
                "DeviceSync Integration", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private void OnTransferItemDoubleClicked(object sender, MouseButtonEventArgs e)
    {
        if (TransfersList.SelectedItem is TransferDisplayItem item && !string.IsNullOrWhiteSpace(item.LocalPath))
        {
            try
            {
                if (File.Exists(item.LocalPath))
                {
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = item.LocalPath,
                        UseShellExecute = true
                    });
                }
                else if (Directory.Exists(App.StorageSettings.DownloadFolder))
                {
                    App.StorageSettings.OpenInExplorer();
                }
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"Could not open file: {ex.Message}");
            }
        }
    }

    // ─── Drag & Drop ────────────────────────────────────────────────────

    private void OnWindowDragEnter(object sender, DragEventArgs e)
    {
        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            DropOverlay.Visibility = Visibility.Visible;
            e.Effects = DragDropEffects.Copy;
        }
    }

    private void OnWindowDragLeave(object sender, DragEventArgs e)
    {
        DropOverlay.Visibility = Visibility.Collapsed;
    }

    private void OnWindowDrop(object sender, DragEventArgs e)
    {
        DropOverlay.Visibility = Visibility.Collapsed;
        if (e.Data.GetDataPresent(DataFormats.FileDrop))
        {
            var files = (string[]?)e.Data.GetData(DataFormats.FileDrop);
            if (files != null && files.Length > 0)
            {
                OnTabTransfersClicked(this, new RoutedEventArgs());
                App.TransferQueue.EnqueueUploads(files);
            }
        }
    }

    // ─── Cancel transfer ─────────────────────────────────────────────────

    private void OnCancelTransferClicked(object sender, RoutedEventArgs e)
    {
        if (sender is System.Windows.Controls.Button btn && btn.Tag is string id)
            App.TransferQueue.Cancel(id);
    }

    // ─── Progress & Event updates ─────────────────────────────────────────

    private void OnFileTransferCompleted(TransferEntry entry)
    {
        Dispatcher.Invoke(RefreshTransfers);
    }

    private void OnStatusChanged(string status)
    {
        _ = Dispatcher.BeginInvoke(() =>
        {
            StatusText.Text = status;

            if (App.SyncEngine.IsConnected)
            {
                StatusDot.Fill = BrushGreen;
                var modeLabel = App.SyncEngine.IsHotspotMode ? "Hotspot Direct (Zero-Copy)" : "Shared Wi-Fi (AES-256-GCM)";
                DetailText.Text = $"Connected via WebSocket • {modeLabel}";

                var connectedIp = App.SyncEngine.ConnectedIp;
                if (!string.IsNullOrEmpty(connectedIp))
                    FooterText.Text = $"Connected to {connectedIp}:7777";
            }
            else if (status.Contains("Scanning") || status.Contains("Connecting") || status.Contains("found"))
            {
                StatusDot.Fill = BrushYellow;
                DetailText.Text = status;
            }
            else
            {
                StatusDot.Fill = BrushRed;
                DetailText.Text = "Connect to phone hotspot or same Wi-Fi to begin";
                FooterText.Text = "Waiting for Android agent...";
            }

            UpdateEncryptionStatus();
        });
    }

    private void RefreshHistory()
    {
        var items = App.Database.GetAllItems();
        _clipboardItems.Clear();
        foreach (var item in items.Take(10))
        {
            _clipboardItems.Add(new ClipboardDisplayItem
            {
                Content = item.Content,
                Source = item.Source == "android" ? "📱 Android" : "💻 Windows",
                TimeDisplay = DateTimeOffset.FromUnixTimeMilliseconds(item.Timestamp)
                    .LocalDateTime.ToString("HH:mm:ss")
            });
        }
    }

    private void RefreshTransfers()
    {
        var items = App.Database.GetAllTransfers();
        _transferItems.Clear();
        foreach (var item in items.Take(20))
        {
            var isSent = item.Direction == "sent";
            var icon = GetIcon(item.MimeType, item.FileName);

            _transferItems.Add(new TransferDisplayItem
            {
                FileName = item.FileName,
                FormattedSize = FormatBytes(item.FileSize),
                DirectionDisplay = isSent ? "↑ Sent to Phone" : "↓ Received",
                DirectionColor = new SolidColorBrush(isSent ? Color.FromRgb(0x89, 0xB4, 0xFA) : Color.FromRgb(0xA6, 0xE3, 0xA1)),
                TimeDisplay = DateTimeOffset.FromUnixTimeMilliseconds(item.Timestamp)
                    .LocalDateTime.ToString("HH:mm:ss"),
                IconText = icon,
                LocalPath = item.LocalPath
            });
        }
    }

    private static string GetIcon(string mimeType, string fileName)
    {
        var ext = Path.GetExtension(fileName).ToLowerInvariant();
        if (mimeType.StartsWith("image/") || ext is ".jpg" or ".jpeg" or ".png" or ".webp" or ".heic" or ".gif") return "🖼️";
        if (mimeType.StartsWith("video/") || ext is ".mp4" or ".mkv" or ".mov" or ".avi") return "🎬";
        if (mimeType.StartsWith("audio/") || ext is ".mp3" or ".wav" or ".flac") return "🎵";
        if (mimeType.Contains("pdf") || ext == ".pdf") return "📕";
        if (mimeType.Contains("zip") || mimeType.Contains("rar") || ext is ".zip" or ".rar" or ".7z") return "📦";
        return "📄";
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes <= 0) return "0 B";
        string[] units = { "B", "KB", "MB", "GB" };
        int digitGroups = (int)(Math.Log10(bytes) / Math.Log10(1024));
        digitGroups = Math.Clamp(digitGroups, 0, 3);
        return $"{bytes / Math.Pow(1024, digitGroups):F1} {units[digitGroups]}";
    }

    private void UpdateEncryptionStatus()
    {
        if (App.SyncEngine.IsEncrypted)
        {
            EncryptionStatusIcon.Text = "🔒";
            EncryptionStatusText.Text = "Encrypted session active (AES-256-GCM)";
            EncryptionStatusText.Foreground = BrushGreen;
        }
        else if (App.SyncEngine.IsConnected)
        {
            EncryptionStatusIcon.Text = "🔑";
            EncryptionStatusText.Text = "Key exchange in progress...";
            EncryptionStatusText.Foreground = BrushYellow;
        }
        else
        {
            EncryptionStatusIcon.Text = "🔓";
            EncryptionStatusText.Text = "Not connected";
            EncryptionStatusText.Foreground = BrushMuted;
        }
    }

    private void DirectIpButton_Click(object sender, RoutedEventArgs e)
    {
        DirectIpInput.Text = App.StorageSettings?.LastConnectedIp ?? "";
        DirectIpOverlay.Visibility = Visibility.Visible;
        DirectIpInput.Focus();
        DirectIpInput.SelectAll();
    }

    private void DirectIpCancel_Click(object sender, RoutedEventArgs e)
    {
        DirectIpOverlay.Visibility = Visibility.Collapsed;
    }

    private async void DirectIpConnect_Click(object sender, RoutedEventArgs e)
    {
        var ip = DirectIpInput.Text.Trim();
        if (string.IsNullOrWhiteSpace(ip)) return;

        DirectIpConnectButton.IsEnabled = false;
        DirectIpConnectButton.Content = "Connecting...";

        try
        {
            var success = await App.Monitor.ConnectDirectAsync(ip);
            if (success)
            {
                DirectIpOverlay.Visibility = Visibility.Collapsed;
            }
        }
        finally
        {
            DirectIpConnectButton.IsEnabled = true;
            DirectIpConnectButton.Content = "Connect";
        }
    }

    private void DirectIpInput_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            DirectIpConnect_Click(sender, e);
        }
        else if (e.Key == Key.Escape)
        {
            DirectIpCancel_Click(sender, e);
        }
    }
}

public class ClipboardDisplayItem
{
    public string Content { get; set; } = "";
    public string Source { get; set; } = "";
    public string TimeDisplay { get; set; } = "";
}

public class TransferDisplayItem
{
    public string FileName { get; set; } = "";
    public string FormattedSize { get; set; } = "";
    public string DirectionDisplay { get; set; } = "";
    public Brush DirectionColor { get; set; } = Brushes.White;
    public string TimeDisplay { get; set; } = "";
    public string IconText { get; set; } = "📄";
    public string? LocalPath { get; set; }
}