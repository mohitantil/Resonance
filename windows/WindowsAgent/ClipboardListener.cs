using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace WindowsAgent
{
    /// <summary>
    /// Listens for Windows clipboard changes using the AddClipboardFormatListener Win32 API.
    /// This is more reliable than polling and fires immediately on any clipboard update.
    /// </summary>
    public class ClipboardListener : IDisposable
    {
        private const int WM_CLIPBOARDUPDATE = 0x031D;
        private HwndSource? _hwndSource;
        private IntPtr _windowHandle;

        public event Action<string>? ClipboardTextChanged;

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool AddClipboardFormatListener(IntPtr hwnd);

        [DllImport("user32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

        /// <summary>
        /// Start listening. Must be called after the WPF window has loaded (has a valid HWND).
        /// </summary>
        public void Start(Window window)
        {
            var helper = new WindowInteropHelper(window);
            _windowHandle = helper.Handle;

            if (_windowHandle == IntPtr.Zero)
            {
                Debug.WriteLine("ClipboardListener: Window handle is null, deferring...");
                return;
            }

            _hwndSource = HwndSource.FromHwnd(_windowHandle);
            _hwndSource?.AddHook(WndProc);

            bool success = AddClipboardFormatListener(_windowHandle);
            Debug.WriteLine($"ClipboardListener: Registered = {success}");
        }

        private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == WM_CLIPBOARDUPDATE)
            {
                // Non-blocking async retrieval with retry to allow locking process to release clipboard
                _ = ReadClipboardWithRetryAsync();
            }
            return IntPtr.Zero;
        }

        private async System.Threading.Tasks.Task ReadClipboardWithRetryAsync(int maxRetries = 7, int initialDelayMs = 30)
        {
            for (int i = 0; i < maxRetries; i++)
            {
                try
                {
                    if (Clipboard.ContainsText())
                    {
                        string text = Clipboard.GetText();
                        if (!string.IsNullOrEmpty(text))
                        {
                            ClipboardTextChanged?.Invoke(text);
                            return;
                        }
                    }
                    else
                    {
                        // Successfully opened and verified it doesn't contain text (e.g. image or files)
                        return;
                    }
                }
                catch (COMException comEx)
                {
                    Debug.WriteLine($"ClipboardListener: Lock contention on attempt {i + 1}/{maxRetries}: {comEx.Message}");
                }
                catch (Exception ex)
                {
                    Debug.WriteLine($"ClipboardListener: Error reading clipboard on attempt {i + 1}: {ex.Message}");
                }

                if (i < maxRetries - 1)
                {
                    await System.Threading.Tasks.Task.Delay(initialDelayMs * (i + 1));
                }
            }
        }

        public void Dispose()
        {
            if (_windowHandle != IntPtr.Zero)
            {
                RemoveClipboardFormatListener(_windowHandle);
            }
            _hwndSource?.RemoveHook(WndProc);
        }
    }
}
