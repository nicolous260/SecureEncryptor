package com.secureencryptor.app.crypto

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH_BITS = 256
    private const val IV_LENGTH_BYTES = 12
    private const val SALT_LENGTH_BYTES = 16
    private const val ITERATIONS = 100000
    private const val GCM_TAG_LENGTH_BITS = 128

    val MAGIC_BYTES = byteArrayOf(0x45, 0x4E, 0x43, 0x32) // "ENC2"
    const val VERSION: Byte = 1

    private val secureRandom = SecureRandom()

    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt,
            ITERATIONS,
            KEY_LENGTH_BITS
        )
        val factory = SecretKeyFactory.getInstance(KEY_FACTORY_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypt plaintext string to Base64 output matching ENC2 spec.
     */
    fun encryptText(password: String, plaintext: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val secretKey = deriveKey(password, salt)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val outputStream = ByteArrayOutputStream()
        outputStream.write(MAGIC_BYTES)
        outputStream.write(VERSION.toInt())
        outputStream.write(salt)
        outputStream.write(iv)
        outputStream.write(ciphertext)

        return Base64.getEncoder().encodeToString(outputStream.toByteArray())
    }

    /**
     * Decrypt Base64 ENC2 formatted string to plaintext.
     */
    fun decryptText(password: String, encryptedBase64: String): String {
        val data = try {
            Base64.getDecoder().decode(encryptedBase64.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64 encrypted data", e)
        }

        val minHeaderLength = MAGIC_BYTES.size + 1 + SALT_LENGTH_BYTES + IV_LENGTH_BYTES
        if (data.size < minHeaderLength) {
            throw IllegalArgumentException("Invalid encrypted data format")
        }

        // Verify magic bytes
        for (i in MAGIC_BYTES.indices) {
            if (data[i] != MAGIC_BYTES[i]) {
                throw IllegalArgumentException("Invalid encrypted data format")
            }
        }

        var offset = MAGIC_BYTES.size
        val version = data[offset++]
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported format version: $version")
        }

        val salt = data.copyOfRange(offset, offset + SALT_LENGTH_BYTES)
        offset += SALT_LENGTH_BYTES

        val iv = data.copyOfRange(offset, offset + IV_LENGTH_BYTES)
        offset += IV_LENGTH_BYTES

        val ciphertext = data.copyOfRange(offset, data.size)

        try {
            val secretKey = deriveKey(password, salt)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plaintextBytes = cipher.doFinal(ciphertext)
            return String(plaintextBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw SecurityException("Decryption failed: Wrong password or corrupted data", e)
        }
    }

    /**
     * Encrypt file bytes/stream matching ENC2 file spec.
     * Output structure: [MAGIC][VERSION][SALT][IV][FILENAME_LEN (2 bytes)][FILENAME][CIPHERTEXT]
     */
    fun encryptFile(
        password: String,
        originalFileName: String,
        inputData: ByteArray,
        progressCallback: ((Int) -> Unit)? = null
    ): ByteArray {
        progressCallback?.invoke(10)
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val secretKey = deriveKey(password, salt)

        progressCallback?.invoke(30)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(inputData)
        progressCallback?.invoke(80)

        val fileNameBytes = originalFileName.toByteArray(StandardCharsets.UTF_8)
        val fileNameLength = fileNameBytes.size

        val output = ByteArrayOutputStream()
        output.write(MAGIC_BYTES)
        output.write(VERSION.toInt())
        output.write(salt)
        output.write(iv)
        output.write((fileNameLength shr 8) and 0xFF)
        output.write(fileNameLength and 0xFF)
        output.write(fileNameBytes)
        output.write(ciphertext)

        progressCallback?.invoke(100)
        return output.toByteArray()
    }

    data class DecryptedFile(
        val fileName: String,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecryptedFile
            if (fileName != other.fileName) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = fileName.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Decrypt file bytes matching ENC2 file spec.
     */
    fun decryptFile(
        password: String,
        encryptedFileData: ByteArray,
        progressCallback: ((Int) -> Unit)? = null
    ): DecryptedFile {
        progressCallback?.invoke(10)
        val minHeaderLength = MAGIC_BYTES.size + 1 + SALT_LENGTH_BYTES + IV_LENGTH_BYTES + 2
        if (encryptedFileData.size < minHeaderLength) {
            throw IllegalArgumentException("Invalid encrypted file format")
        }

        for (i in MAGIC_BYTES.indices) {
            if (encryptedFileData[i] != MAGIC_BYTES[i]) {
                throw IllegalArgumentException("Invalid file format")
            }
        }

        var offset = MAGIC_BYTES.size
        val version = encryptedFileData[offset++]
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported file format version: $version")
        }

        val salt = encryptedFileData.copyOfRange(offset, offset + SALT_LENGTH_BYTES)
        offset += SALT_LENGTH_BYTES

        val iv = encryptedFileData.copyOfRange(offset, offset + IV_LENGTH_BYTES)
        offset += IV_LENGTH_BYTES

        val fileNameLength = ((encryptedFileData[offset].toInt() and 0xFF) shl 8) or
                (encryptedFileData[offset + 1].toInt() and 0xFF)
        offset += 2

        if (offset + fileNameLength > encryptedFileData.size) {
            throw IllegalArgumentException("Corrupted file header")
        }

        val fileNameBytes = encryptedFileData.copyOfRange(offset, offset + fileNameLength)
        val fileName = String(fileNameBytes, StandardCharsets.UTF_8)
        offset += fileNameLength

        val ciphertext = encryptedFileData.copyOfRange(offset, encryptedFileData.size)

        progressCallback?.invoke(40)
        try {
            val secretKey = deriveKey(password, salt)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val decryptedBytes = cipher.doFinal(ciphertext)
            progressCallback?.invoke(100)
            return DecryptedFile(fileName, decryptedBytes)
        } catch (e: Exception) {
            throw SecurityException("Decryption failed: Wrong password or corrupted file", e)
        }
    }
}
