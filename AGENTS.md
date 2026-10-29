# DeviceSync (Resonance) Agent Guidelines & Architecture Rules

## 1. Baseline Preservation & Currently Running Binaries (CRITICAL)

Before introducing any changes on `fix/large-shares`, the exact working baseline state of the running system is preserved here:

- **Git Baseline**:
  - Base Branch: `main` at commit `f42e211acde98e6b1b928eed032280c9e23879e4` (`fix(clipboard): resolve Windows-to-Android clipboard sync and background restrictions`).
  - Active Feature Branch: `fix/large-shares`.

- **Windows Agent (.exe) Currently Running**:
  - **Actively Running Binary**: `d:\softwares\SyncDevice\windows\WindowsAgent\bin\Debug\net9.0-windows10.0.19041.0\WindowsAgent.exe`
  - **Running Process Details**: Process ID `21736` (spawned via dotnet runner PID `2416` / CMD script).
  - **File Size & Timestamp**: 156,160 bytes | Modified: `18-10-2026 18:20:36`.
  - **Standalone Release Copy**: `d:\softwares\SyncDevice\Releases\SyncDevice-WindowsAgent.exe`.
  - **Dedicated Safe Backup**:
    - `d:\softwares\SyncDevice\backups\working_v3.1_baseline\WindowsAgent-v3.1-running.exe`
    - Full dependency bin directory backed up at: `d:\softwares\SyncDevice\backups\working_v3.1_baseline\windows_agent_bin\`

- **Android Agent (.apk) Currently Running**:
  - **Installed APK Binary**: `d:\softwares\SyncDevice\android\app\build\outputs\apk\debug\app-debug.apk`
  - **File Size & Timestamp**: 116,168,239 bytes | Modified: `13-10-2026 20:53:03`.
  - **Release Copy**: `d:\softwares\SyncDevice\Releases\SyncDevice-Android-debug.apk`.
  - **Dedicated Safe Backup**:
    - `d:\softwares\SyncDevice\backups\working_v3.1_baseline\DeviceSync-Android-v3.1-running.apk`

### Rollback / Recovery Procedures
If any changes need to be reverted:
1. **Restore Windows Agent**:
   ```powershell
   Stop-Process -Name "WindowsAgent" -Force -ErrorAction SilentlyContinue
   Copy-Item "d:\softwares\SyncDevice\backups\working_v3.1_baseline\windows_agent_bin\*" "d:\softwares\SyncDevice\windows\WindowsAgent\bin\Debug\net9.0-windows10.0.19041.0\" -Recurse -Force
   ```
2. **Reinstall Baseline Android APK**:
   ```powershell
   adb install -r "d:\softwares\SyncDevice\backups\working_v3.1_baseline\DeviceSync-Android-v3.1-running.apk"
   ```
3. **Revert Git Code**:
   ```powershell
   git checkout main
   ```

---

## 2. Portable .NET SDK Environment (CRITICAL)
- **Global `dotnet` is NOT installed** on the system PATH.
- The portable .NET 9.0 SDK is located at: `d:\softwares\SyncDevice\dotnet_sdk`.
- **Always prefix/set environment variables** before running any `dotnet` command in PowerShell:
  ```powershell
  $env:DOTNET_ROOT = "d:\softwares\SyncDevice\dotnet_sdk"
  $env:PATH = "d:\softwares\SyncDevice\dotnet_sdk;$env:PATH"
  dotnet build windows/WindowsAgent/WindowsAgent.csproj
  ```
  Or directly invoke: `& "d:\softwares\SyncDevice\dotnet_sdk\dotnet.exe" <subcommand>`

---

## 3. Windows Agent Process Locking
- `WindowsAgent.exe` runs as a single-instance WPF desktop application (guarded by `Mutex(ShellIntegration.MutexName)`).
- When `WindowsAgent.exe` is already running, attempting to run `dotnet build` or `dotnet run` will fail with file lock errors (`MSB3026` / `MSB3027: The process cannot access the file ... WindowsAgent.exe because it is being used by another process`).
- Always check running processes before rebuilding:
  ```powershell
  Get-Process -Name "*WindowsAgent*", "*dotnet*" -ErrorAction SilentlyContinue
  ```
- If a build fails with file locks, stop the running process:
  ```powershell
  Stop-Process -Name "WindowsAgent" -Force
  ```

---

## 4. Git Branching Rules
- Git refs **cannot contain spaces** (`fatal: '<name>' is not a valid branch name`).
- Use repository standard kebab-case naming conventions:
  - Fixes: `fix/<short-description>` (e.g. `fix/large-shares`, `fix/connection-resilience`)
  - Features: `feature/<short-description>` (e.g. `feature/bidirectional-file-transfer`)

---

## 5. Current Codebase Architecture & State

### High-Level Roles
- **Android Agent (Server)**:
  - Kotlin 2.2, Android 8.0+ (API 26+), Jetpack Compose UI (Catppuccin Mocha theme).
  - Background Service: `SyncForegroundService` runs Ktor 3.1 Netty HTTP server on port 7777 + WebSocket `/sync`.
  - Discovery Responders: UDP Broadcast responder (port 7777) + mDNS (`_devicesync._tcp.` via `NsdManager`).
  - Storage: Room DB v3 (`SyncDatabase`: `ClipboardDao`, `TransferDao`, `TrustedDeviceDao`) + MediaStore scoped storage / SAF.
- **Windows Agent (Client)**:
  - C# 13, .NET 9.0 WPF (Catppuccin Mocha theme).
  - Entry Point & Single Instance: `App.xaml.cs` (Mutex + Named Pipe IPC for File Explorer `Send to`).
  - Discovery: `NetworkMonitor.cs` (UDP broadcast beacon, OS DHCP default gateway queries for Hotspot/USB tethering, parallel `Task.WhenAny` probe, direct IP fallback modal).
  - WebSockets & Crypto: `ClipboardSyncEngine.cs` (ClientWebSocket, 30s heartbeat keep-alive, message router).
  - Transfers: `FileDownloader.cs` (HTTP GET `/transfer/{token}`), `FileUploader.cs` (HTTP POST `/upload` streaming), coordinated by `TransferQueue.cs`.

### Protocols & Cryptography
1. **Discovery**:
   - Android listens on port 7777 and responds to UDP broadcast `{"type":"devicesync_ping"}`.
   - Also announces via mDNS `_devicesync._tcp`.
   - Windows probes discovered IPs + DHCP gateway with `GET /hello`.
2. **End-to-End Encryption (E2EE)**:
   - Dynamic **ECDHE P-256** session key exchange over WebSocket `/sync`.
   - Windows sends its ephemeral public key and machine details (`deviceId`, `deviceName`).
   - Android prompts user on first pair (or checks `TrustedDeviceDao` in Room DB).
   - Once approved, Android returns its public key and a unique `sessionToken`.
   - Both derive shared secret with AES-256-GCM.
   - HTTP requests (`/transfers`, `/transfer/{token}`, `/upload`) require header `Authorization: Bearer <sessionToken>`.
3. **Clipboard Sync**:
   - Bidirectional real-time sync with deduplication and loop prevention (`clipboardId` GUID tracking).
   - Windows uses COM retry loop (`SetClipboardWithRetry`) to handle locked clipboard exceptions.
4. **File Transfers**:
   - **Android ➔ Windows**: Files registered in `FileTransferManager`, notified via WebSocket `files_available`, downloaded via HTTP GET `/transfer/{token}`, cleaned up via `DELETE /transfer/{token}`.
   - **Windows ➔ Android**: Streamed via HTTP POST `/upload` with `X-File-Name` and `X-File-Size` headers, saved directly to MediaStore / Download folder.
5. **Feature Scope Note**:
   - Notification mirroring and call intercept were discarded in commits `1eba41d` and `9c06d2a`. Current production scope focuses exclusively on **Clipboard Sync**, **Bidirectional File Transfers**, and **E2EE / Zero-Config Discovery**.

---

## 6. Large Shares & Streaming Notes (Reference: `codebaseAlterGuide1ByArgon.md`)
- **Android Cache Staging Bottleneck**: Android's `FileTransferManager` stages shared URIs by copying to `context.cacheDir/outgoing_transfers`. For very large files (e.g. 1.8 GB - 5 GB movies) or multi-file batches, this risks:
  1. Main-thread ANR in `ShareReceiverActivity.onCreate()`.
  2. Cache directory quota exhaustion (`ENOSPC`).
  3. Premature marking as `"completed"` in Room DB before the PC downloads bytes.
- **Windows Progress Bar Wiring**: `App.xaml.cs` currently routes `FilesAvailableReceived` to `FileDownloader.CheckAndDownloadAsync()` directly instead of through `App.TransferQueue.EnqueueDownloads()`, bypassing the observable progress UI.
- Detailed implementation steps and root causes are documented in [`codebaseAlterGuide1ByArgon.md`](file:///d:/softwares/SyncDevice/codebaseAlterGuide1ByArgon.md).


---

## 7. Windows 11 Application Control / WDAC Policy Enforcement (CRITICAL)

### Symptom & Root Cause
- When running `WindowsAgent.exe` directly or via certain runners, Windows 11 throws:
  `Unhandled exception: An error occurred trying to start process '...WindowsAgent.exe'... An Application Control policy has blocked this file.`
- **Event Log Verification**: `Microsoft-Windows-CodeIntegrity/Operational` Event ID `3077`:
  `Code Integrity determined that a process (\Device\...\dotnet.exe) attempted to load ...\WindowsAgent.exe that did not meet the Enterprise signing level requirements or violated code integrity policy (Policy ID:{0283ac0f-fff1-49ae-ada1-8a933130cad6}).`
- This is caused by Windows 11 **Smart App Control (SAC)** or WDAC policy enforcing code integrity on newly compiled unsigned native apphost PE wrappers (`.exe`).

### Sanctioned Running Method
- **Do not invoke `WindowsAgent.exe` directly.**
- The portable .NET 9.0 SDK binary `dotnet_sdk\dotnet.exe` is officially signed by Microsoft Corporation and is fully trusted by WDAC and Smart App Control.
- Always run the managed assembly `WindowsAgent.dll` through the Microsoft host:
  ```powershell
  & "d:\softwares\SyncDevice\dotnet_sdk\dotnet.exe" "d:\softwares\SyncDevice\windows\WindowsAgent\bin\Debug\net9.0-windows10.0.19041.0\WindowsAgent.dll"
  ```
- Or simply run the root wrapper scripts:
  - CMD / Double-click: `d:\softwares\SyncDevice\Run-WindowsAgent.cmd`
  - PowerShell: `d:\softwares\SyncDevice\Run-WindowsAgent.ps1`

---

## 8. Audit Round 4 Fixes Implemented

- **BUG-01 (UI Thread Dispatch Flood)**:
  - `TransferQueue.cs`: Added equality guards in `Status`, `Progress`, and `TransferredBytes`. Replaced blocking `Dispatcher.Invoke` with `Dispatcher.BeginInvoke(DispatcherPriority.DataBind)`.
  - Added 80ms throttle (`UpdateItemProgressThrottled`) preventing ~490k cross-thread UI freezes on 5 GB transfers.
  - `FileUploader.cs`: Throttled `StatusChanged` to only fire when integer percentage changes.
- **BUG-02 (Concurrency / ObjectDisposedException)**:
  - `NetworkMonitor.cs`: Added `await Task.WhenAll(tasks)` before disposing `SemaphoreSlim` and `CancellationTokenSource` in `ProbeCandidateListAsync`.
- **SEC-01 (Access Control / Session Revocation)**:
  - `SyncForegroundService.kt`: Stored `deviceId` on `ClientSession` and implemented `revokeAndDisconnectDevice(deviceId)` closing active WebSockets and rejecting HTTP requests immediately upon revocation.
  - `SyncViewModel.kt`: Hooked `revokeTrustedDevice` to call `revokeAndDisconnectDevice`.
- **BUG-03 (Compose Recomposition Flood)**:
  - `FileTransferManager.kt`: Throttled `_progressFlow` emissions during streaming to only emit when percent changes or at least 1 MB transferred.
- **BUG-04 (Infinite Pairing Loop & Coroutine Leak)**:
  - `ClipboardSyncEngine.cs`: Added `WasRejectedByPeer` and handled `WebSocketCloseStatus.PolicyViolation`.
  - `NetworkMonitor.cs`: Halts auto-reconnect spam when `WasRejectedByPeer` is true; resets on manual direct connect or network change.
  - `SyncForegroundService.kt`: Scoped `pendingConnectionRequest` to `sessionId` with 60s timeout.
- **BUG-05 (Android Notification Progress)**:
  - `SyncForegroundService.kt`: Added throttled foreground notification updates during `GET /transfer/{token}` streaming and completion/failure toasts in `DELETE /transfer/{token}`.
- **BUG-06 (URI Permission Lifespan)**:
  - `FileTransferManager.kt`: Added `grantUriPermission(context.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)` in `bindUrisToServiceLifetime`.
  - `ShareReceiverActivity.kt`: Cleaned up dead parameter in `ensureSyncServiceRunning`.
- **BUG-07 (UI Thread Disk I/O)**:
  - `TransferQueue.cs`: Offloaded directory enumeration and `FileInfo` queries to background thread (`Task.Run`).
- **BUG-08 (Socket & SQLite Resilience)**:
  - `SyncForegroundService.kt`: `DatagramSocket(null).apply { reuseAddress = true; bind(...) }`.
  - `ClipboardDatabase.cs`: Enabled SQLite WAL mode (`PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; Default Timeout=5; Pooling=True`).
  - `NetworkMonitor.cs`: Used native .NET 9 `udp.ReceiveAsync(cts.Token)`.


---

## 9. Audit Round 5 Fixes Implemented

- **BUG-01 (Windows Defender `.part` File Lock at 100%)**:
  - `FileDownloader.cs`: Added `MoveFileWithRetryAsync` with 8 retry attempts and exponential backoff (150ms * attempt) when moving `.part` to final filename, defeating Windows Defender's `IRP_MJ_CLEANUP` file scan lock.
- **BUG-02 (Clipboard Echo Loop)**:
  - `SyncForegroundService.kt`: Added `markLocalClipboardWrite(content)` and `@Volatile lastProgrammaticClip`. Replaced single-use `lastSetContent` clear.
  - `ClipboardCopyActivity.kt` & `SyncViewModel.kt`: Registered local clipboard writes prior to `setPrimaryClip`.
- **BUG-03 (Zombie Socket / Half-Open Hang)**:
  - `ClipboardSyncEngine.cs`: Added `_lastReceiveTick` tracking. Added `ForceCleanupDeadSocket()` called on remote close and socket errors. Heartbeat drops dead socket if no frame arrives within 75s.
- **BUG-04 (Duplicate DB Rows on Upload Retry)**:
  - `FileUploader.cs`: Added `X-Transfer-Id` header to `POST /upload`.
  - `TransferQueue.cs`: Passed `item.Id` as `transferId`.
  - `SyncForegroundService.kt`: Used `call.request.header("X-Transfer-Id")` to reuse the existing Room DB row across retries.
- **BUG-05 (Memory Leak on Abandoned Shares)**:
  - `FileTransferManager.kt`: Added `createdAt: Long` on `TransferInfo` and `pruneStaleTransfers(context)` running at the start of `registerTransfers` (30m cutoff).
- **BUG-06 (UI Status Bar Overwrite & Brush Churn)**:
  - `NetworkMonitor.cs`: Exits `ProbeAndConnectAsync` early if `_syncEngine.IsConnected` is true, preventing 6s timer from wiping active transfer status.
  - `MainWindow.xaml.cs`: Pre-allocated static frozen `SolidColorBrush` instances (`BrushGreen`, `BrushYellow`, `BrushRed`, `BrushMuted`) and switched to `Dispatcher.BeginInvoke`.
- **BUG-07 (Concurrent Pairing Dialog & Notification Typo)**:
  - `SyncForegroundService.kt`: Declines stale pending requests before replacing with a new one. Fixed spacing in `"Sending ${info.fileName}: $pct%"`.
- **DEAD-01 (Dead Imports & Duplicated KDoc)**:
  - Cleaned unused imports in `SessionCrypto.kt` (`ByteBuffer`) and `ShareReceiverActivity.kt` (`ClipData`). Removed duplicated KDoc in `SyncForegroundService.kt`.
