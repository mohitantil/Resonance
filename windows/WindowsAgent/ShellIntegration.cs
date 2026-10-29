using System;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace WindowsAgent
{
    /// <summary>
    /// Handles Windows Shell integration (SendTo context menu) and Single-Instance Named Pipe IPC.
    /// </summary>
    public static class ShellIntegration
    {
        public const string PipeName = "DeviceSyncWindowsAgentPipe";
        public const string MutexName = "DeviceSyncWindowsAgentMutex";

        /// <summary>
        /// Registers a shortcut in the user's SendTo folder (%APPDATA%\Microsoft\Windows\SendTo).
        /// Right-clicking any file in Windows File Explorer -> Send to -> "Send to Android (DeviceSync)"
        /// will pass the files to this application.
        /// </summary>
        public static bool RegisterSendToShortcut()
        {
            try
            {
                var sendToPath = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "Microsoft", "Windows", "SendTo");

                Directory.CreateDirectory(sendToPath);
                var shortcutLocation = Path.Combine(sendToPath, "Send to Android (DeviceSync).lnk");

                var currentExe = Process.GetCurrentProcess().MainModule?.FileName;
                if (string.IsNullOrEmpty(currentExe) || !File.Exists(currentExe))
                {
                    // Fallback to BaseDirectory executable
                    currentExe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "WindowsAgent.exe");
                }

                var currentDir = Path.GetDirectoryName(currentExe) ?? AppDomain.CurrentDomain.BaseDirectory;

                var escShortcut = shortcutLocation.Replace("'", "''");
                var escExe = currentExe.Replace("'", "''");
                var escDir = currentDir.Replace("'", "''");

                var psCommand = $"$s = (New-Object -ComObject WScript.Shell).CreateShortcut('{escShortcut}'); " +
                                $"$s.TargetPath = '{escExe}'; " +
                                $"$s.WorkingDirectory = '{escDir}'; " +
                                $"$s.Description = 'Send files to Android via DeviceSync'; " +
                                $"$s.Save()";

                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = $"-NoProfile -NonInteractive -Command \"{psCommand}\"",
                    CreateNoWindow = true,
                    UseShellExecute = false
                };

                using var process = Process.Start(psi);
                process?.WaitForExit(3000);
                return File.Exists(shortcutLocation);
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"ShellIntegration: Failed to register SendTo shortcut: {ex.Message}");
                return false;
            }
        }

        /// <summary>
        /// Check if the SendTo shortcut is already registered.
        /// </summary>
        public static bool IsSendToRegistered()
        {
            var sendToPath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "Microsoft", "Windows", "SendTo");
            var shortcutLocation = Path.Combine(sendToPath, "Send to Android (DeviceSync).lnk");
            return File.Exists(shortcutLocation);
        }

        /// <summary>
        /// Starts the background Named Pipe listener for receiving file paths from secondary instances.
        /// </summary>
        public static void StartIpcServer(Action<string[]> onFilesReceived, CancellationToken ct)
        {
            Task.Run(async () =>
            {
                while (!ct.IsCancellationRequested)
                {
                    try
                    {
                        using var pipeServer = new NamedPipeServerStream(
                            PipeName,
                            PipeDirection.In,
                            NamedPipeServerStream.MaxAllowedServerInstances,
                            PipeTransmissionMode.Byte,
                            PipeOptions.Asynchronous);

                        await pipeServer.WaitForConnectionAsync(ct);

                        using var reader = new StreamReader(pipeServer, Encoding.UTF8);
                        var content = await reader.ReadToEndAsync(ct);

                        var files = content.Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries);
                        if (files.Length > 0)
                        {
                            onFilesReceived(files);
                        }
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                    catch (Exception ex)
                    {
                        Debug.WriteLine($"ShellIntegration: IPC Server loop error: {ex.Message}");
                        await Task.Delay(1000, ct);
                    }
                }
            }, ct);
        }

        /// <summary>
        /// Sends file arguments to the already-running primary instance via Named Pipe.
        /// Returns true if sent successfully.
        /// </summary>
        public static bool SendToPrimaryInstance(string[] args)
        {
            try
            {
                using var pipeClient = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                pipeClient.Connect(1500); // Wait up to 1.5s for primary instance

                using var writer = new StreamWriter(pipeClient, Encoding.UTF8);
                foreach (var arg in args)
                {
                    if (File.Exists(arg) || Directory.Exists(arg))
                    {
                        writer.WriteLine(arg);
                    }
                }
                writer.Flush();
                return true;
            }
            catch (Exception ex)
            {
                Debug.WriteLine($"ShellIntegration: Could not connect to primary instance: {ex.Message}");
                return false;
            }
        }
    }
}
