using System;
using System.Buffers.Binary;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace WindowsAgent
{
    /// <summary>
    /// Manages ECDHE P-256 key exchange and AES-256-GCM encryption for session payloads.
    ///
    /// Lifecycle:
    /// 1. Call <see cref="GenerateKeyPair"/> at session start.
    /// 2. Send <see cref="GetPublicKeyBase64"/> to the remote peer.
    /// 3. When the peer's public key arrives, call <see cref="DeriveSharedSecret"/>.
    /// 4. Use <see cref="Encrypt"/> / <see cref="Decrypt"/> for all sensitive payloads.
    /// </summary>
    public class SessionCrypto
    {
        private const int GcmTagLengthBytes = 16;   // 128 bits
        private const int GcmIvLengthBytes = 12;
        public const int StreamChunkSize = 64 * 1024;

        private ECDiffieHellman? _localKeyPair;
        private volatile byte[]? _sharedSecret;

        /// <summary>
        /// Whether a shared secret has been derived (key exchange is complete).
        /// </summary>
        public bool IsEstablished => _sharedSecret != null;

        /// <summary>
        /// Generate the local ECDHE P-256 key pair. Call once per WebSocket session.
        /// </summary>
        public void GenerateKeyPair()
        {
            _localKeyPair?.Dispose();
            _localKeyPair = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
            _sharedSecret = null; // reset on re-key
            Debug.WriteLine("SessionCrypto: Generated ECDHE P-256 key pair");
        }

        /// <summary>
        /// Returns the local public key as a Base64-encoded string (X.509 SubjectPublicKeyInfo DER).
        /// This is what we send to the Android peer in the key_exchange message.
        /// </summary>
        public string GetPublicKeyBase64()
        {
            if (_localKeyPair == null)
                throw new InvalidOperationException("Key pair not generated. Call GenerateKeyPair() first.");

            var pubBytes = _localKeyPair.PublicKey.ExportSubjectPublicKeyInfo();
            return Convert.ToBase64String(pubBytes);
        }

        /// <summary>
        /// Given the remote peer's Base64-encoded public key (X.509 DER),
        /// derive the shared AES-256 secret.
        /// </summary>
        public void DeriveSharedSecret(string remotePublicKeyBase64)
        {
            if (_localKeyPair == null)
                throw new InvalidOperationException("Key pair not generated.");

            var remoteKeyBytes = Convert.FromBase64String(remotePublicKeyBase64);

            using var remoteKey = ECDiffieHellman.Create();
            remoteKey.ImportSubjectPublicKeyInfo(remoteKeyBytes, out _);

            // Raw ECDH shared secret
            var rawSecret = _localKeyPair.DeriveRawSecretAgreement(remoteKey.PublicKey);

            // SHA-256 hash for uniform key distribution (matches Android side)
            _sharedSecret = SHA256.HashData(rawSecret);

            Debug.WriteLine("SessionCrypto: ECDHE shared secret derived successfully");
        }

        /// <summary>
        /// Encrypt a plaintext string using AES-256-GCM.
        /// Returns Base64 in format: iv_base64:ciphertext_base64
        /// </summary>
        public string Encrypt(string plaintext)
        {
            var key = _sharedSecret ?? throw new InvalidOperationException("Shared secret not established.");

            var plaintextBytes = Encoding.UTF8.GetBytes(plaintext);
            var iv = RandomNumberGenerator.GetBytes(GcmIvLengthBytes);
            var ciphertext = new byte[plaintextBytes.Length];
            var tag = new byte[GcmTagLengthBytes];

            using var aes = new AesGcm(key, GcmTagLengthBytes);
            aes.Encrypt(iv, plaintextBytes, ciphertext, tag);

            // Combine ciphertext + tag (Android's Cipher.doFinal appends the tag)
            var combined = new byte[ciphertext.Length + tag.Length];
            Buffer.BlockCopy(ciphertext, 0, combined, 0, ciphertext.Length);
            Buffer.BlockCopy(tag, 0, combined, ciphertext.Length, tag.Length);

            var ivBase64 = Convert.ToBase64String(iv);
            var ctBase64 = Convert.ToBase64String(combined);
            return $"{ivBase64}:{ctBase64}";
        }

        /// <summary>
        /// Decrypt a ciphertext string produced by Encrypt (or the Android equivalent).
        /// Input format: iv_base64:ciphertext_base64
        /// </summary>
        public string Decrypt(string encryptedPayload)
        {
            var key = _sharedSecret ?? throw new InvalidOperationException("Shared secret not established.");

            var parts = encryptedPayload.Split(':', 2);
            if (parts.Length != 2)
                throw new ArgumentException("Invalid encrypted payload format");

            var iv = Convert.FromBase64String(parts[0]);
            var combined = Convert.FromBase64String(parts[1]);

            // Split combined into ciphertext + tag (last 16 bytes = tag)
            var ciphertext = new byte[combined.Length - GcmTagLengthBytes];
            var tag = new byte[GcmTagLengthBytes];
            Buffer.BlockCopy(combined, 0, ciphertext, 0, ciphertext.Length);
            Buffer.BlockCopy(combined, ciphertext.Length, tag, 0, GcmTagLengthBytes);

            var plaintext = new byte[ciphertext.Length];
            using var aes = new AesGcm(key, GcmTagLengthBytes);
            aes.Decrypt(iv, ciphertext, tag, plaintext);

            return Encoding.UTF8.GetString(plaintext);
        }

        /// <summary>
        /// Encrypt a stream using 64 KB AES-256-GCM chunks.
        /// </summary>
        public async Task EncryptStreamChunkedAsync(
            Stream input,
            Stream output,
            Action<long>? onBytesProcessed = null,
            CancellationToken ct = default)
        {
            var key = _sharedSecret ?? throw new InvalidOperationException("Shared secret not established.");

            var baseIv = RandomNumberGenerator.GetBytes(GcmIvLengthBytes);
            await output.WriteAsync(baseIv, ct);

            var plainBuf = new byte[StreamChunkSize];
            var cipherBuf = new byte[StreamChunkSize + GcmTagLengthBytes];
            var lenBytes = new byte[4];
            ulong chunkIndex = 0;
            long totalPlain = 0;

            using var aes = new AesGcm(key, GcmTagLengthBytes);

            while (true)
            {
                int readTotal = 0;
                while (readTotal < plainBuf.Length)
                {
                    int r = await input.ReadAsync(plainBuf.AsMemory(readTotal, plainBuf.Length - readTotal), ct);
                    if (r == 0) break;
                    readTotal += r;
                }
                if (readTotal == 0) break;

                var chunkIv = DeriveChunkIv(baseIv, chunkIndex++);
                aes.Encrypt(
                    chunkIv,
                    plainBuf.AsSpan(0, readTotal),
                    cipherBuf.AsSpan(0, readTotal),
                    cipherBuf.AsSpan(readTotal, GcmTagLengthBytes));

                BinaryPrimitives.WriteInt32BigEndian(lenBytes, readTotal);
                await output.WriteAsync(lenBytes, ct);
                await output.WriteAsync(cipherBuf.AsMemory(0, readTotal + GcmTagLengthBytes), ct);

                totalPlain += readTotal;
                onBytesProcessed?.Invoke(totalPlain);
            }

            // Write authenticated 0-length EOF chunk
            var eofIv = DeriveChunkIv(baseIv, chunkIndex);
            var eofTag = new byte[GcmTagLengthBytes];
            aes.Encrypt(eofIv, ReadOnlySpan<byte>.Empty, Span<byte>.Empty, eofTag);
            BinaryPrimitives.WriteInt32BigEndian(lenBytes, 0);
            await output.WriteAsync(lenBytes, ct);
            await output.WriteAsync(eofTag, ct);
            await output.FlushAsync(ct);
        }

        /// <summary>
        /// Decrypt a stream produced by 64 KB AES-256-GCM chunks.
        /// </summary>
        public async Task<long> DecryptStreamChunkedAsync(
            Stream input,
            Stream output,
            Action<long>? onBytesProcessed = null,
            CancellationToken ct = default)
        {
            var key = _sharedSecret ?? throw new InvalidOperationException("Shared secret not established.");

            var baseIv = new byte[GcmIvLengthBytes];
            await input.ReadExactlyAsync(baseIv, ct);

            var lenBytes = new byte[4];
            var encryptedBuf = new byte[StreamChunkSize + GcmTagLengthBytes];
            var plainBuf = new byte[StreamChunkSize];
            ulong chunkIndex = 0;
            long totalPlain = 0;

            using var aes = new AesGcm(key, GcmTagLengthBytes);

            while (true)
            {
                await input.ReadExactlyAsync(lenBytes, ct);
                int plainLen = BinaryPrimitives.ReadInt32BigEndian(lenBytes);
                if (plainLen < 0 || plainLen > StreamChunkSize)
                    throw new CryptographicException($"Invalid encrypted chunk length: {plainLen}");

                int totalChunkLen = plainLen + GcmTagLengthBytes;
                await input.ReadExactlyAsync(encryptedBuf.AsMemory(0, totalChunkLen), ct);

                var chunkIv = DeriveChunkIv(baseIv, chunkIndex++);
                aes.Decrypt(
                    chunkIv,
                    encryptedBuf.AsSpan(0, plainLen),
                    encryptedBuf.AsSpan(plainLen, GcmTagLengthBytes),
                    plainBuf.AsSpan(0, plainLen));

                if (plainLen == 0) break; // Authenticated EOF reached

                await output.WriteAsync(plainBuf.AsMemory(0, plainLen), ct);
                totalPlain += plainLen;
                onBytesProcessed?.Invoke(totalPlain);
            }

            await output.FlushAsync(ct);
            return totalPlain;
        }

        private static byte[] DeriveChunkIv(byte[] baseIv, ulong chunkIndex)
        {
            var iv = (byte[])baseIv.Clone();
            Span<byte> counter = stackalloc byte[8];
            BinaryPrimitives.WriteUInt64BigEndian(counter, chunkIndex);
            for (int i = 0; i < 8; i++)
                iv[4 + i] ^= counter[i];
            return iv;
        }

        /// <summary>
        /// Reset the crypto state (e.g. on disconnect).
        /// </summary>
        public void Reset()
        {
            _localKeyPair?.Dispose();
            _localKeyPair = null;
            _sharedSecret = null;
            Debug.WriteLine("SessionCrypto: Session crypto state reset");
        }
    }
}
