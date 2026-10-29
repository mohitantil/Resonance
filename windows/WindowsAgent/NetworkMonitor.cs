using System;



using System.Collections.Generic;



using System.Diagnostics;



using System.Linq;



using System.Net;



using System.Net.Http;



using System.Net.NetworkInformation;



using System.Net.Sockets;



using System.Text.Json;



using System.Threading;



using System.Threading.Tasks;







namespace WindowsAgent



{



    /// <summary>



    /// Monitors network changes and dynamically discovers the Android agent



    /// on any local network (Home Wi-Fi, Hotspot, USB Tethering) using:



    ///  1. Dynamic Gateway Resolution (queries active OS network adapters for DHCP gateway)



    ///  2. UDP Broadcast Beacon (for Home/Office Wi-Fi LAN discovery without IP guessing)



    ///  3. Fast Parallel Probing with Task.WhenAny (instant connection < 100ms)



    ///  4. Persistent Last-Known IP and Direct Manual IP fallback



    /// </summary>



    public class NetworkMonitor



    {



        public const int SignalingPort = 7777;







        private static readonly HttpClient _httpClient = new HttpClient



        {



            Timeout = TimeSpan.FromSeconds(3)



        };







        private readonly ClipboardSyncEngine _syncEngine;



        private Timer? _retryTimer;



        private bool _probing = false;



        private string? _lastConnectedIp = null;







        public event Action<string>? StatusChanged;







        public NetworkMonitor(ClipboardSyncEngine syncEngine)



        {



            _syncEngine = syncEngine;



            _lastConnectedIp = App.StorageSettings?.LastConnectedIp;



        }







        public void Start()



        {



            NetworkChange.NetworkAddressChanged += OnNetworkChanged;



            Debug.WriteLine("NetworkMonitor: Started — scanning for Android agent.");



            StatusChanged?.Invoke("Scanning for Android agent...");







            // Initial check on startup



            _ = ProbeAndConnectAsync();







            // Retry every 6 seconds if disconnected so we reconnect promptly



            _retryTimer = new Timer(



                _ => _ = ProbeAndConnectAsync(),



                null,



                TimeSpan.FromSeconds(6),



                TimeSpan.FromSeconds(6));



        }







        public void Stop()



        {



            NetworkChange.NetworkAddressChanged -= OnNetworkChanged;



            _retryTimer?.Dispose();



        }







        private void OnNetworkChanged(object? sender, EventArgs e)

        {

            Debug.WriteLine("NetworkMonitor: Network address changed — re-scanning.");

            _syncEngine.ResetRejectionState();

            _ = ProbeAndConnectAsync();

        }



        public async Task ProbeAndConnectAsync()
        {
            if (_probing || _syncEngine.IsConnected) return;

            // Do not spam the phone every 6s if the user explicitly declined pairing on Android

            if (_syncEngine.WasRejectedByPeer)

            {

                StatusChanged?.Invoke("Connection declined on phone (use Direct IP to retry)");

                return;

            }



            _probing = true;



            try

            {

                StatusChanged?.Invoke("Scanning for Android agent...");



                var candidates = await BuildCandidateListAsync();

                string? foundIp = await ProbeCandidateListAsync(candidates, timeoutMs: 2000);



                if (string.IsNullOrEmpty(foundIp))

                {

                    var subnetCandidates = BuildLocalSubnetCandidates(candidates);

                    if (subnetCandidates.Count > 0)

                    {

                        foundIp = await ProbeCandidateListAsync(subnetCandidates, timeoutMs: 550, maxConcurrency: 32);

                    }

                }



                if (!string.IsNullOrEmpty(foundIp))

                {

                    await ConnectToAgentAsync(foundIp);

                }

                else

                {

                    StatusChanged?.Invoke("Android agent not found. Waiting...");

                }

            }

            finally

            {

                _probing = false;

            }

        }



        private async Task<string?> ProbeCandidateListAsync(List<string> ips, int timeoutMs, int maxConcurrency = 16)

        {

            if (ips.Count == 0) return null;



            using var cts = new CancellationTokenSource();

            using var sem = new SemaphoreSlim(maxConcurrency);



            var tasks = ips.Select(async ip =>

            {

                try

                {

                    await sem.WaitAsync(cts.Token).ConfigureAwait(false);

                }

                catch (OperationCanceledException)

                {

                    return null;

                }



                try

                {

                    return await ProbeIpAsync(ip, timeoutMs, cts.Token).ConfigureAwait(false);

                }

                finally

                {

                    sem.Release();

                }

            }).ToList();



            string? foundIp = null;

            var pending = new List<Task<string?>>(tasks);



            while (pending.Count > 0)

            {

                var finished = await Task.WhenAny(pending).ConfigureAwait(false);

                pending.Remove(finished);

                try

                {

                    var ip = await finished.ConfigureAwait(false);

                    if (!string.IsNullOrEmpty(ip))

                    {

                        foundIp = ip;

                        cts.Cancel();

                        break;

                    }

                }

                catch { }

            }



            // Wait for cancelled in-flight tasks to exit their finally { sem.Release(); } block

            // BEFORE 'using var sem' disposes the SemaphoreSlim (fixes BUG-02 ObjectDisposedException).

            try

            {

                await Task.WhenAll(tasks).ConfigureAwait(false);

            }

            catch { }



            return foundIp;

        }



        private static List<string> BuildLocalSubnetCandidates(ICollection<string> alreadyProbed)



        {



            var list = new List<string>();



            foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())



            {



                if (iface.OperationalStatus != OperationalStatus.Up ||



                    iface.NetworkInterfaceType == NetworkInterfaceType.Loopback)



                    continue;







                foreach (var unicast in iface.GetIPProperties().UnicastAddresses)



                {



                    if (unicast.Address.AddressFamily != AddressFamily.InterNetwork) continue;



                    var bytes = unicast.Address.GetAddressBytes();



                    // Only sweep private IPv4 ranges (192.168.x.x, 10.x.x.x, 172.16-31.x.x)



                    bool isPrivate = (bytes[0] == 192 && bytes[1] == 168) ||



                                     bytes[0] == 10 ||



                                     (bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31);



                    if (!isPrivate) continue;







                    var prefix = $"{bytes[0]}.{bytes[1]}.{bytes[2]}";



                    for (int host = 1; host <= 254; host++)



                    {



                        if (host == bytes[3]) continue;



                        var candidate = $"{prefix}.{host}";



                        if (!alreadyProbed.Contains(candidate) && !list.Contains(candidate))



                            list.Add(candidate);



                    }



                }



            }



            return list;



        }







        /// <summary>



        /// Connects directly to a specific IP entered manually or discovered.



        /// </summary>



        public async Task<bool> ConnectDirectAsync(string ip)

        {

            if (string.IsNullOrWhiteSpace(ip)) return false;

            ip = ip.Trim();



            _syncEngine.ResetRejectionState();

            StatusChanged?.Invoke($"Connecting to {ip}...");

            var probeResult = await ProbeIpAsync(ip, 2000, CancellationToken.None);

            if (!string.IsNullOrEmpty(probeResult))

            {

                return await ConnectToAgentAsync(probeResult);

            }



            StatusChanged?.Invoke($"Failed to reach Android agent at {ip}");

            return false;

        }



/// <summary>



        /// Builds an ordered, dynamic list of IP addresses to probe.



        /// No machine-specific or phone-specific IPs are hardcoded.



        /// </summary>



        private async Task<List<string>> BuildCandidateListAsync()



        {



            var candidates = new List<string>();







            // 1. Last successfully connected IP (fastest reconnect)



            var lastIp = _lastConnectedIp ?? App.StorageSettings?.LastConnectedIp;



            if (!string.IsNullOrWhiteSpace(lastIp) && !candidates.Contains(lastIp))



            {



                candidates.Add(lastIp);



            }







            // 2. Discover via UDP broadcast beacon (works instantly on Home/Office Wi-Fi)



            var udpIps = await DiscoverViaUdpAsync(timeoutMs: 400);



            foreach (var ip in udpIps)



            {



                if (!candidates.Contains(ip))



                    candidates.Add(ip);



            }







            // 3. Dynamic Gateway resolution: Query Windows OS network adapters for active DHCP gateways



            // (on Mobile Hotspot / USB Tethering, the phone is the default gateway assigned by DHCP)



            foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())



            {



                if (iface.OperationalStatus != OperationalStatus.Up) continue;



                if (iface.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;







                var props = iface.GetIPProperties();



                foreach (var gw in props.GatewayAddresses)



                {



                    var gwIp = gw.Address.ToString();



                    if (!string.IsNullOrWhiteSpace(gwIp) && gwIp != "0.0.0.0" && !candidates.Contains(gwIp))



                    {



                        candidates.Add(gwIp);



                    }



                }



            }







            // 4. Common fallback gateway addresses (only if no gateways were detected by OS)



            if (candidates.Count <= 1)



            {



                var standardHotspotGateways = new[] { "192.168.43.1", "192.168.137.1", "172.20.10.1" };



                foreach (var fallback in standardHotspotGateways)



                {



                    if (!candidates.Contains(fallback))



                        candidates.Add(fallback);



                }



            }







            return candidates;



        }







        /// <summary>



        /// Broadcasts a lightweight UDP discovery packet on port 7777 to find any Android



        /// device running DeviceSync on the local Wi-Fi network.



        /// </summary>



        private async Task<List<string>> DiscoverViaUdpAsync(int timeoutMs)



        {



            var discovered = new List<string>();



            try



            {



                using var udp = new UdpClient();



                udp.EnableBroadcast = true;







                var pingBytes = System.Text.Encoding.UTF8.GetBytes("{\"type\":\"devicesync_ping\"}");







                var broadcastEp = new IPEndPoint(IPAddress.Broadcast, SignalingPort);



                await udp.SendAsync(pingBytes, pingBytes.Length, broadcastEp);







                foreach (var iface in NetworkInterface.GetAllNetworkInterfaces())



                {



                    if (iface.OperationalStatus != OperationalStatus.Up || iface.NetworkInterfaceType == NetworkInterfaceType.Loopback)



                        continue;







                    var ipProps = iface.GetIPProperties();



                    foreach (var unicast in ipProps.UnicastAddresses)



                    {



                        if (unicast.Address.AddressFamily == AddressFamily.InterNetwork && unicast.IPv4Mask != null)



                        {



                            var subnetBroadcast = GetSubnetBroadcastAddress(unicast.Address, unicast.IPv4Mask);



                            try



                            {



                                await udp.SendAsync(pingBytes, pingBytes.Length, new IPEndPoint(subnetBroadcast, SignalingPort));



                            }



                            catch { }



                        }



                    }



                }







                using var cts = new CancellationTokenSource(timeoutMs);



                while (!cts.IsCancellationRequested)



                {



                    try



                    {



                        var res = await udp.ReceiveAsync(cts.Token);



                        var ip = res.RemoteEndPoint.Address.ToString();



                        if (!discovered.Contains(ip) && ip != "127.0.0.1")



                        {



                            Debug.WriteLine($"NetworkMonitor: UDP beacon found agent at {ip}");



                            discovered.Add(ip);



                        }



                    }



                    catch (OperationCanceledException)



                    {



                        break;



                    }



                    catch



                    {



                        break;



                    }



                }



            }



            catch (Exception ex)



            {



                Debug.WriteLine($"NetworkMonitor: UDP discovery exception: {ex.Message}");



            }



            return discovered;



        }







        private static IPAddress GetSubnetBroadcastAddress(IPAddress address, IPAddress mask)



        {



            var ipBytes = address.GetAddressBytes();



            var maskBytes = mask.GetAddressBytes();



            var broadcast = new byte[ipBytes.Length];



            for (int i = 0; i < ipBytes.Length; i++)



            {



                broadcast[i] = (byte)(ipBytes[i] | (maskBytes[i] ^ 255));



            }



            return new IPAddress(broadcast);



        }







        private async Task<string?> ProbeIpAsync(string ip, int timeoutMs, CancellationToken ct)



        {



            try



            {



                using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct);



                linkedCts.CancelAfter(TimeSpan.FromMilliseconds(timeoutMs));







                using var response = await _httpClient.GetAsync($"http://{ip}:{SignalingPort}/hello", linkedCts.Token);



                if (response.IsSuccessStatusCode)



                {



                    var body = await response.Content.ReadAsStringAsync(linkedCts.Token);



                    if (body.Contains("protocol") || body.Contains("device"))



                    {



                        Debug.WriteLine($"NetworkMonitor: Discovered Android agent at {ip} ({body})");



                        return ip;



                    }



                }



            }



            catch { }



            return null;



        }







        /// <summary>



        /// Updates all endpoints with the discovered IP, saves to settings, and connects WebSocket.



        /// </summary>



        private async Task<bool> ConnectToAgentAsync(string ip)



        {



            try



            {



                StatusChanged?.Invoke($"Android agent found at {ip} — connecting...");







                _syncEngine.UpdateEndpoint(ip, SignalingPort);



                App.FileDownloader.UpdateEndpoint(ip, SignalingPort);



                App.FileUploader.UpdateEndpoint(ip, SignalingPort);



                _lastConnectedIp = ip;







                if (App.StorageSettings != null)



                {



                    App.StorageSettings.LastConnectedIp = ip;



                }







                await _syncEngine.ConnectAsync();







                if (_syncEngine.IsConnected)



                {



                    StatusChanged?.Invoke($"Connected ✓  ({ip})");



                    return true;



                }



            }



            catch (Exception ex)



            {



                Debug.WriteLine($"NetworkMonitor: Error connecting to {ip}: {ex.Message}");



            }







            return false;



        }



    }



}



