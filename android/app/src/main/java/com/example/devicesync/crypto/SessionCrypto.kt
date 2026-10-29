package com.example.devicesync.crypto

import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SessionCrypto {

    companion object {
        private const val TAG = "SessionCrypto"
        private const val EC_ALGORITHM = "EC"
        private const val EC_CURVE = "secp256r1"
        private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = 16
        private const val GCM_IV_LENGTH_BYTES = 12
        const val STREAM_CHUNK_SIZE = 64 * 1024 // 64 KB
    }

    @Volatile
    private var localKeyPair: java.security.KeyPair? = null

    @Volatile
    private var sharedSecret: SecretKey? = null

    private val secureRandom = SecureRandom()

    val isEstablished: Boolean
        get() = sharedSecret != null

    fun generateKeyPair() {
        val kpg = KeyPairGenerator.getInstance(EC_ALGORITHM)
        kpg.initialize(ECGenParameterSpec(EC_CURVE))
        localKeyPair = kpg.generateKeyPair()
        sharedSecret = null
        Log.d(TAG, "Generated ECDHE P-256 key pair")
    }

    fun getPublicKeyBase64(): String {
        val pub = localKeyPair?.public
            ?: throw IllegalStateException("Key pair not generated. Call generateKeyPair() first.")
        return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    fun deriveSharedSecret(remotePublicKeyBase64: String) {
        val priv = localKeyPair?.private
            ?: throw IllegalStateException("Key pair not generated.")
        val remoteKeyBytes = Base64.decode(remotePublicKeyBase64, Base64.NO_WRAP)
        val keySpec = X509EncodedKeySpec(remoteKeyBytes)
        val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
        val remotePublicKey = keyFactory.generatePublic(keySpec) as ECPublicKey

        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
        keyAgreement.init(priv)
        keyAgreement.doPhase(remotePublicKey, true)

        val rawSecret = keyAgreement.generateSecret()
        val derivedKey = MessageDigest.getInstance("SHA-256").digest(rawSecret)
        sharedSecret = SecretKeySpec(derivedKey, AES_ALGORITHM)
        Log.d(TAG, "ECDHE shared secret derived successfully")
    }

    fun encrypt(plaintext: String): String {
        val key = sharedSecret ?: throw IllegalStateException("Shared secret not established.")
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
    }

    fun decrypt(encryptedPayload: String): String {
        val key = sharedSecret ?: throw IllegalStateException("Shared secret not established.")
        val parts = encryptedPayload.split(":", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Invalid encrypted payload format")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Zero-allocation 64 KB chunked AES-256-GCM stream encryption (supports 5 GB+ files).
     */
    fun encryptStreamChunked(
        input: InputStream,
        output: OutputStream,
        onBytesProcessed: (Long) -> Unit
    ) {
        val key = sharedSecret ?: throw IllegalStateException("Shared secret not established.")
        val dataOut = DataOutputStream(output)
        val baseIv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        dataOut.write(baseIv)

        val plainBuffer = ByteArray(STREAM_CHUNK_SIZE)
        val cipherBuffer = ByteArray(STREAM_CHUNK_SIZE + GCM_TAG_LENGTH_BYTES)
        val chunkIv = ByteArray(GCM_IV_LENGTH_BYTES)
        var chunkIndex = 0L
        var totalPlainBytes = 0L
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)

        while (true) {
            var readTotal = 0
            while (readTotal < plainBuffer.size) {
                val r = input.read(plainBuffer, readTotal, plainBuffer.size - readTotal)
                if (r == -1) break
                readTotal += r
            }
            if (readTotal == 0) break

            fillChunkIv(baseIv, chunkIndex++, chunkIv)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv))
            val outLen = cipher.doFinal(plainBuffer, 0, readTotal, cipherBuffer, 0)

            dataOut.writeInt(readTotal)
            dataOut.write(cipherBuffer, 0, outLen)
            totalPlainBytes += readTotal
            onBytesProcessed(totalPlainBytes)
        }

        // Write authenticated 0-length EOF chunk
        fillChunkIv(baseIv, chunkIndex, chunkIv)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv))
        val eofLen = cipher.doFinal(plainBuffer, 0, 0, cipherBuffer, 0)
        dataOut.writeInt(0)
        dataOut.write(cipherBuffer, 0, eofLen)
        dataOut.flush()
    }

    /**
     * Zero-allocation 64 KB chunked AES-256-GCM stream decryption (supports 5 GB+ files).
     */
    fun decryptStreamChunked(
        input: InputStream,
        output: OutputStream,
        onBytesProcessed: (Long) -> Unit
    ): Long {
        val key = sharedSecret ?: throw IllegalStateException("Shared secret not established.")
        val dataIn = DataInputStream(input)
        val baseIv = ByteArray(GCM_IV_LENGTH_BYTES)
        dataIn.readFully(baseIv)

        val encryptedBuf = ByteArray(STREAM_CHUNK_SIZE + GCM_TAG_LENGTH_BYTES)
        val plainBuf = ByteArray(STREAM_CHUNK_SIZE)
        val chunkIv = ByteArray(GCM_IV_LENGTH_BYTES)
        var chunkIndex = 0L
        var totalPlainBytes = 0L
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)

        while (true) {
            val plainLen = try {
                dataIn.readInt()
            } catch (e: EOFException) {
                throw SecurityException("Unexpected EOF before authenticated termination tag", e)
            }
            if (plainLen < 0 || plainLen > STREAM_CHUNK_SIZE) {
                throw SecurityException("Invalid chunk length: $plainLen")
            }

            val encryptedLen = plainLen + GCM_TAG_LENGTH_BYTES
            dataIn.readFully(encryptedBuf, 0, encryptedLen)

            fillChunkIv(baseIv, chunkIndex++, chunkIv)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv))
            val decodedLen = cipher.doFinal(encryptedBuf, 0, encryptedLen, plainBuf, 0)

            if (plainLen == 0) break // Verified EOF tag

            output.write(plainBuf, 0, decodedLen)
            totalPlainBytes += decodedLen
            onBytesProcessed(totalPlainBytes)
        }
        output.flush()
        return totalPlainBytes
    }

    private fun fillChunkIv(baseIv: ByteArray, chunkIndex: Long, destIv: ByteArray) {
        System.arraycopy(baseIv, 0, destIv, 0, GCM_IV_LENGTH_BYTES)
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            val counterByte = ((chunkIndex ushr shift) and 0xFFL).toInt()
            destIv[4 + i] = (destIv[4 + i].toInt() xor counterByte).toByte()
        }
    }

    fun reset() {
        localKeyPair = null
        sharedSecret = null
    }
}
