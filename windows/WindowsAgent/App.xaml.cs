using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows;

namespace WindowsAgent;

/// <summary>
/// Application entry point and service container.
/// Supports single-instance mutex and IPC for SendTo right-click context menu actions.
/// </summary>
public partial class App : Application
{
    private static Mutex? _instanceMutex;
    private static CancellationTokenSource _ipcCancel = new();

    // Shared services — initialized once, used across the app
    public static StorageSettings StorageSettings { get; } = new StorageSettings();
    public static ClipboardDatabase Database { get; } = new ClipboardDatabase();
    public static ClipboardSyncEngine SyncEngine { get; } = new ClipboardSyncEngine(Database);
    public static FileDownloader FileDownloader { get; } = new FileDownloader(StorageSettings, Database);
    public static FileUploader FileUploader { get; } = new FileUploader(Database);
    public static NetworkMonitor Monitor { get; } = new NetworkMonitor(SyncEngine);
    public static TransferQueue TransferQueue { get; } = new TransferQueue();

    protected override void OnStartup(StartupEventArgs e)
    {
        // Check single-instance mutex
        _instanceMutex = new Mutex(true, ShellIntegration.MutexName, out bool isFirstInstance);

        if (!isFirstInstance)
        {
            // Another instance is already running
            if (e.Args.Length > 0)
            {
                // Send file paths to the running instance via Named Pipe
                ShellIntegration.SendToPrimaryInstance(e.Args);
            }
            // Exit secondary instance
            Shutdown();
            return;
        }

        base.OnStartup(e);

        // Wire immediate download on WebSocket file notification
        SyncEngine.FilesAvailableReceived += () =>
        {
            _ = FileDownloader.FetchAndEnqueuePendingTransfersAsync(TransferQueue);
        };

        // Start IPC server to receive files from right-click context menu
        ShellIntegration.StartIpcServer(files =>
        {
            Dispatcher.Invoke(() =>
            {
                TransferQueue.EnqueueUploads(files);
            });
        }, _ipcCancel.Token);

        // Start network monitor & discovery
        Monitor.Start();

        // Process any files or folders passed at startup (e.g. right-click SendTo)
        if (e.Args.Length > 0)
        {
            var validPaths = e.Args.Where(p => File.Exists(p) || Directory.Exists(p)).ToArray();
            if (validPaths.Length > 0)
                TransferQueue.EnqueueUploads(validPaths);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _ipcCancel.Cancel();
        Monitor.Stop();
        _ = SyncEngine.DisconnectAsync();
        _instanceMutex?.ReleaseMutex();
        base.OnExit(e);
    }
}
