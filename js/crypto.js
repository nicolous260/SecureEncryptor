/**
 * Secure Encryptor - Crypto Module
 * Handles all encryption/decryption operations using Web Crypto API
 */

const SecureCrypto = (function() {
    'use strict';

    // Constants matching Android app
    const ALGORITHM = 'AES-GCM';
    const KEY_LENGTH = 256;
    const IV_LENGTH = 12;
    const SALT_LENGTH = 16;
    const ITERATIONS = 100000;
    const MAGIC_BYTES = new Uint8Array([0x45, 0x4E, 0x43, 0x32]); // "ENC2"
    const VERSION = 1;
    const CHUNK_SIZE = 64 * 1024; // 64KB chunks for file processing

    /**
     * Derive encryption key from password using PBKDF2
     * @param {string} password - User password
     * @param {Uint8Array} salt - Random salt
     * @returns {Promise<CryptoKey>} Derived AES key
     */
    async function deriveKey(password, salt) {
        const encoder = new TextEncoder();
        const passwordBuffer = encoder.encode(password);

        const keyMaterial = await crypto.subtle.importKey(
            'raw',
            passwordBuffer,
            'PBKDF2',
            false,
            ['deriveKey']
        );

        return await crypto.subtle.deriveKey(
            {
                name: 'PBKDF2',
                salt: salt,
                iterations: ITERATIONS,
                hash: 'SHA-256'
            },
            keyMaterial,
            { name: ALGORITHM, length: KEY_LENGTH },
            false,
            ['encrypt', 'decrypt']
        );
    }

    /**
     * Encrypt text using AES-256-GCM
     * @param {string} password - Encryption password
     * @param {string} plaintext - Text to encrypt
     * @returns {Promise<string>} Base64 encoded encrypted data
     */
    async function encryptText(password, plaintext) {
        // Generate random salt and IV
        const salt = crypto.getRandomValues(new Uint8Array(SALT_LENGTH));
        const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));

        // Derive key
        const key = await deriveKey(password, salt);

        // Encrypt
        const encoder = new TextEncoder();
        const plaintextBuffer = encoder.encode(plaintext);

        const ciphertext = await crypto.subtle.encrypt(
            { name: ALGORITHM, iv: iv },
            key,
            plaintextBuffer
        );

        // Build format: [MAGIC][VERSION][SALT][IV][CIPHERTEXT]
        const result = new Uint8Array(
            MAGIC_BYTES.length + 1 + SALT_LENGTH + IV_LENGTH + ciphertext.byteLength
        );

        let offset = 0;
        result.set(MAGIC_BYTES, offset);
        offset += MAGIC_BYTES.length;
        result[offset++] = VERSION;
        result.set(salt, offset);
        offset += SALT_LENGTH;
        result.set(iv, offset);
        offset += IV_LENGTH;
        result.set(new Uint8Array(ciphertext), offset);

        // Convert to Base64
        return btoa(String.fromCharCode(...result));
    }

    /**
     * Decrypt text using AES-256-GCM
     * @param {string} password - Decryption password
     * @param {string} encryptedData - Base64 encoded encrypted data
     * @returns {Promise<string>} Decrypted plaintext
     */
    async function decryptText(password, encryptedData) {
        // Decode Base64
        const data = Uint8Array.from(atob(encryptedData), c => c.charCodeAt(0));

        // Verify magic bytes
        const magic = data.slice(0, MAGIC_BYTES.length);
        if (!magic.every((v, i) => v === MAGIC_BYTES[i])) {
            throw new Error('Invalid encrypted data format');
        }

        // Parse components
        let offset = MAGIC_BYTES.length;
        const version = data[offset++];
        const salt = data.slice(offset, offset + SALT_LENGTH);
        offset += SALT_LENGTH;
        const iv = data.slice(offset, offset + IV_LENGTH);
        offset += IV_LENGTH;
        const ciphertext = data.slice(offset);

        // Derive key and decrypt
        const key = await deriveKey(password, salt);

        const plaintext = await crypto.subtle.decrypt(
            { name: ALGORITHM, iv: iv },
            key,
            ciphertext
        );

        const decoder = new TextDecoder();
        return decoder.decode(plaintext);
    }

    /**
     * Encrypt file in chunks
     * @param {string} password - Encryption password
     * @param {File} file - File to encrypt
     * @param {Function} progressCallback - Callback(progressPercent)
     * @returns {Promise<Blob>} Encrypted file blob
     */
    async function encryptFile(password, file, progressCallback = null) {
        const salt = crypto.getRandomValues(new Uint8Array(SALT_LENGTH));
        const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));
        const key = await deriveKey(password, salt);

        // Read file
        const fileData = await file.arrayBuffer();

        if (progressCallback) progressCallback(20);

        // Encrypt
        const encrypted = await crypto.subtle.encrypt(
            { name: ALGORITHM, iv: iv },
            key,
            fileData
        );

        if (progressCallback) progressCallback(80);

        // Build file format: [MAGIC][VERSION][SALT][IV][FILENAME_LEN][FILENAME][ENCRYPTED_DATA]
        const fileNameBytes = new TextEncoder().encode(file.name);
        const result = new Uint8Array(
            MAGIC_BYTES.length + 1 + SALT_LENGTH + IV_LENGTH + 2 + fileNameBytes.length + encrypted.byteLength
        );

        let offset = 0;
        result.set(MAGIC_BYTES, offset);
        offset += MAGIC_BYTES.length;
        result[offset++] = VERSION;
        result.set(salt, offset);
        offset += SALT_LENGTH;
        result.set(iv, offset);
        offset += IV_LENGTH;
        result[offset++] = (fileNameBytes.length >> 8) & 0xFF;
        result[offset++] = fileNameBytes.length & 0xFF;
        result.set(fileNameBytes, offset);
        offset += fileNameBytes.length;
        result.set(new Uint8Array(encrypted), offset);

        if (progressCallback) progressCallback(100);

        return new Blob([result], { type: 'application/octet-stream' });
    }

    /**
     * Decrypt file
     * @param {string} password - Decryption password
     * @param {File} file - Encrypted file
     * @param {Function} progressCallback - Callback(progressPercent)
     * @returns {Promise<{name: string, blob: Blob}>} Original filename and decrypted blob
     */
    async function decryptFile(password, file, progressCallback = null) {
        const fileData = await file.arrayBuffer();
        const data = new Uint8Array(fileData);

        if (progressCallback) progressCallback(10);

        // Verify magic bytes
        const magic = data.slice(0, MAGIC_BYTES.length);
        if (!magic.every((v, i) => v === MAGIC_BYTES[i])) {
            throw new Error('Invalid file format');
        }

        // Parse header
        let offset = MAGIC_BYTES.length;
        const version = data[offset++];

        const salt = data.slice(offset, offset + SALT_LENGTH);
        offset += SALT_LENGTH;

        const iv = data.slice(offset, offset + IV_LENGTH);
        offset += IV_LENGTH;

        const fileNameLength = (data[offset] << 8) | data[offset + 1];
        offset += 2;

        const fileNameBytes = data.slice(offset, offset + fileNameLength);
        const originalFileName = new TextDecoder().decode(fileNameBytes);
        offset += fileNameLength;

        const encryptedData = data.slice(offset);

        if (progressCallback) progressCallback(30);

        // Decrypt
        const key = await deriveKey(password, salt);

        const decrypted = await crypto.subtle.decrypt(
            { name: ALGORITHM, iv: iv },
            key,
            encryptedData
        );

        if (progressCallback) progressCallback(100);

        return {
            name: originalFileName,
            blob: new Blob([decrypted])
        };
    }

    /**
     * Calculate password strength
     * @param {string} password - Password to check
     * @returns {Object} Score (0-4), label, and color
     */
    function checkPasswordStrength(password) {
        let score = 0;

        if (password.length >= 8) score++;
        if (password.length >= 12) score++;
        if (/[A-Z]/.test(password)) score++;
        if (/[a-z]/.test(password)) score++;
        if (/[0-9]/.test(password)) score++;
        if (/[^A-Za-z0-9]/.test(password)) score++;

        score = Math.min(4, Math.floor(score / 1.5));

        const colors = ['#ef4444', '#f97316', '#eab308', '#22c55e', '#16a34a'];
        const labels = ['Very Weak', 'Weak', 'Fair', 'Strong', 'Very Strong'];

        return {
            score: score,
            label: labels[score],
            color: colors[score],
            percent: (score + 1) * 20
        };
    }

    /**
     * Generate a secure random password
     * @param {number} length - Password length (default: 24)
     * @returns {string} Random password
     */
    function generatePassword(length = 24) {
        const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*';
        const array = new Uint32Array(length);
        crypto.getRandomValues(array);
        return Array.from(array, x => chars[x % chars.length]).join('');
    }

    // Public API
    return {
        encryptText,
        decryptText,
        encryptFile,
        decryptFile,
        checkPasswordStrength,
        generatePassword,
        constants: {
            MAGIC_BYTES,
            VERSION,
            CHUNK_SIZE
        }
    };
})();

// Export for module systems if needed
if (typeof module !== 'undefined' && module.exports) {
    module.exports = SecureCrypto;
}
