package com.secureencryptor.app.crypto

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class CryptoEngineTest {

    @Test
    fun testTextEncryptionAndDecryptionRoundtrip() {
        val password = "SuperSecretPassword123!"
        val plaintext = "Hello World! Secure Encryptor Android App"

        val encryptedBase64 = CryptoEngine.encryptText(password, plaintext)
        assertNotNull(encryptedBase64)
        assertTrue(encryptedBase64.isNotEmpty())

        val decryptedText = CryptoEngine.decryptText(password, encryptedBase64)
        assertEquals(plaintext, decryptedText)
    }

    @Test(expected = SecurityException::class)
    fun testTextDecryptionWithWrongPasswordThrowsException() {
        val password = "CorrectPassword123"
        val wrongPassword = "WrongPassword456"
        val plaintext = "Top secret message"

        val encryptedBase64 = CryptoEngine.encryptText(password, plaintext)
        CryptoEngine.decryptText(wrongPassword, encryptedBase64)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidBase64ThrowsException() {
        CryptoEngine.decryptText("password", "NotAValidBase64String!!!")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidMagicBytesThrowsException() {
        // Base64 of 10 bytes without ENC2 header
        val invalidHeader = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35))
        CryptoEngine.decryptText("password", invalidHeader)
    }

    @Test
    fun testFileEncryptionAndDecryptionRoundtrip() {
        val password = "MyFileEncryptionPassword!"
        val originalFileName = "test-document.txt"
        val originalContent = "This is a test file content for encryption and decryption.".toByteArray(StandardCharsets.UTF_8)

        val encryptedFileData = CryptoEngine.encryptFile(password, originalFileName, originalContent)
        assertNotNull(encryptedFileData)
        assertTrue(encryptedFileData.size > originalContent.size)

        val decryptedFile = CryptoEngine.decryptFile(password, encryptedFileData)
        assertEquals(originalFileName, decryptedFile.fileName)
        assertArrayEquals(originalContent, decryptedFile.data)
    }

    @Test
    fun testPasswordStrengthMeter() {
        val weak = PasswordUtils.checkPasswordStrength("12345")
        assertEquals(0, weak.score)
        assertEquals("Very Weak", weak.label)

        val strong = PasswordUtils.checkPasswordStrength("A1b2C3d4E5f6!@#$")
        assertTrue(strong.score >= 3)
    }

    @Test
    fun testPasswordGenerator() {
        val pass = PasswordUtils.generatePassword(24)
        assertEquals(24, pass.length)
        val strength = PasswordUtils.checkPasswordStrength(pass)
        assertTrue(strength.score >= 3)
    }
}
