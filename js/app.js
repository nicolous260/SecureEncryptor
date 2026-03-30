/**
 * Secure Encryptor - App Module
 * Handles UI interactions, event handling, and app state
 */

const app = (function() {
    'use strict';

    // DOM element references
    let elements = {};
    let currentFile = null;
    let currentResult = '';

    /**
     * Initialize the application
     */
    function init() {
        cacheElements();
        bindEvents();
        console.log('Secure Encryptor initialized');
    }

    /**
     * Cache DOM elements for performance
     */
    function cacheElements() {
        elements = {
            password: document.getElementById('password'),
            confirmPassword: document.getElementById('confirmPassword'),
            strengthFill: document.getElementById('strengthFill'),
            strengthText: document.getElementById('strengthText'),
            inputText: document.getElementById('inputText'),
            textResult: document.getElementById('textResult'),
            textResultActions: document.getElementById('textResultActions'),
            dropZone: document.getElementById('dropZone'),
            fileInput: document.getElementById('fileInput'),
            fileInfo: document.getElementById('fileInfo'),
            fileName: document.getElementById('fileName'),
            fileProgress: document.getElementById('fileProgress'),
            fileProgressFill: document.getElementById('fileProgressFill'),
            fileProgressText: document.getElementById('fileProgressText'),
            encryptFileBtn: document.getElementById('encryptFileBtn'),
            decryptFileBtn: document.getElementById('decryptFileBtn'),
            alert: document.getElementById('alert')
        };
    }

    /**
     * Bind event listeners
     */
    function bindEvents() {
        // Password strength checker
        elements.password.addEventListener('input', updatePasswordStrength);

        // File drag and drop
        elements.dropZone.addEventListener('dragover', handleDragOver);
        elements.dropZone.addEventListener('dragleave', handleDragLeave);
        elements.dropZone.addEventListener('drop', handleDrop);
        elements.fileInput.addEventListener('change', handleFileSelect);
    }

    /**
     * Update password strength meter
     */
    function updatePasswordStrength() {
        const password = elements.password.value;
        const strength = SecureCrypto.checkPasswordStrength(password);

        elements.strengthFill.style.width = strength.percent + '%';
        elements.strengthFill.style.background = strength.color;
        elements.strengthText.textContent = 'Strength: ' + strength.label;
        elements.strengthText.style.color = strength.color;
        elements.strengthFill.parentElement.setAttribute('aria-valuenow', strength.percent);
    }

    /**
     * Toggle password visibility
     * @param {string} inputId - ID of password input
     */
    function togglePassword(inputId) {
        const input = document.getElementById(inputId);
        const type = input.type === 'password' ? 'text' : 'password';
        input.type = type;

        // Update aria-label for accessibility
        const button = input.nextElementSibling;
        button.setAttribute('aria-label', type === 'password' ? 'Show password' : 'Hide password');
    }

    /**
     * Switch between tabs
     * @param {string} tabName - 'text' or 'file'
     */
    function switchTab(tabName) {
        // Update tab buttons
        document.querySelectorAll('.tab').forEach((tab, index) => {
            const isActive = (tabName === 'text' && index === 0) || (tabName === 'file' && index === 1);
            tab.classList.toggle('active', isActive);
            tab.setAttribute('aria-selected', isActive);
        });

        // Update tab panels
        document.querySelectorAll('.tab-content').forEach(content => {
            content.classList.remove('active');
            content.hidden = true;
        });

        const activePanel = document.getElementById(tabName + 'Tab');
        if (activePanel) {
            activePanel.classList.add('active');
            activePanel.hidden = false;
        }
    }

    /**
     * Show alert message
     * @param {string} message - Message to display
     * @param {string} type - 'success' or 'error'
     */
    function showAlert(message, type = 'success') {
        elements.alert.textContent = message;
        elements.alert.className = 'alert alert-' + type + ' active';

        // Auto-hide after 5 seconds
        setTimeout(() => {
            hideAlert();
        }, 5000);
    }

    /**
     * Hide alert message
     */
    function hideAlert() {
        elements.alert.classList.remove('active');
    }

    /**
     * Validate password input
     * @returns {boolean} True if valid
     */
    function validatePassword() {
        const password = elements.password.value;
        const confirmPassword = elements.confirmPassword.value;

        if (password.length < 6) {
            showAlert('Password must be at least 6 characters', 'error');
            return false;
        }

        if (password !== confirmPassword) {
            showAlert('Passwords do not match', 'error');
            return false;
        }

        return true;
    }

    /**
     * Encrypt text
     */
    async function encryptText() {
        if (!validatePassword()) return;

        const plaintext = elements.inputText.value;
        if (!plaintext) {
            showAlert('Please enter text to encrypt', 'error');
            return;
        }

        try {
            const password = elements.password.value;
            const result = await SecureCrypto.encryptText(password, plaintext);

            currentResult = result;
            elements.textResult.textContent = result;
            elements.textResult.classList.add('active');
            elements.textResultActions.style.display = 'flex';

            showAlert('Text encrypted successfully!');
        } catch (err) {
            console.error('Encryption error:', err);
            showAlert('Encryption failed: ' + err.message, 'error');
        }
    }

    /**
     * Decrypt text
     */
    async function decryptText() {
        const password = elements.password.value;
        if (!password) {
            showAlert('Please enter password', 'error');
            return;
        }

        const encryptedData = elements.inputText.value;
        if (!encryptedData) {
            showAlert('Please enter encrypted text', 'error');
            return;
        }

        try {
            const result = await SecureCrypto.decryptText(password, encryptedData);

            currentResult = result;
            elements.textResult.textContent = result;
            elements.textResult.classList.add('active');
            elements.textResultActions.style.display = 'flex';

            showAlert('Text decrypted successfully!');
        } catch (err) {
            console.error('Decryption error:', err);
            showAlert('Decryption failed: Wrong password or corrupted data', 'error');
        }
    }

    /**
     * Copy result to clipboard
     */
    async function copyResult() {
        const text = elements.textResult.textContent;
        if (!text) return;

        try {
            await navigator.clipboard.writeText(text);
            showAlert('Copied to clipboard!');
        } catch (err) {
            // Fallback for older browsers
            const textarea = document.createElement('textarea');
            textarea.value = text;
            textarea.style.position = 'fixed';
            textarea.style.opacity = '0';
            document.body.appendChild(textarea);
            textarea.select();

            try {
                document.execCommand('copy');
                showAlert('Copied to clipboard!');
            } catch (e) {
                showAlert('Failed to copy', 'error');
            }

            document.body.removeChild(textarea);
        }
    }

    /**
     * Share result using Web Share API
     */
    async function shareResult() {
        const text = elements.textResult.textContent;
        if (!text) return;

        if (navigator.share) {
            try {
                await navigator.share({
                    title: 'Secure Encryptor',
                    text: text
                });
            } catch (err) {
                // User cancelled or share failed
                if (err.name !== 'AbortError') {
                    showAlert('Sharing failed', 'error');
                }
            }
        } else {
            // Fallback: copy to clipboard
            copyResult();
            showAlert('Sharing not supported. Copied to clipboard instead.');
        }
    }

    /**
     * Download result as file
     */
    function downloadResult() {
        const text = elements.textResult.textContent;
        if (!text) return;

        const blob = new Blob([text], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'encrypted-text.txt';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);

        showAlert('Downloaded!');
    }

    /**
     * Handle drag over event
     * @param {DragEvent} e
     */
    function handleDragOver(e) {
        e.preventDefault();
        e.stopPropagation();
        elements.dropZone.classList.add('dragover');
    }

    /**
     * Handle drag leave event
     * @param {DragEvent} e
     */
    function handleDragLeave(e) {
        e.preventDefault();
        e.stopPropagation();
        elements.dropZone.classList.remove('dragover');
    }

    /**
     * Handle drop event
     * @param {DragEvent} e
     */
    function handleDrop(e) {
        e.preventDefault();
        e.stopPropagation();
        elements.dropZone.classList.remove('dragover');

        const files = e.dataTransfer.files;
        if (files.length > 0) {
            handleFile(files[0]);
        }
    }

    /**
     * Handle file select from input
     * @param {Event} e
     */
    function handleFileSelect(e) {
        const files = e.target.files;
        if (files.length > 0) {
            handleFile(files[0]);
        }
    }

    /**
     * Process selected file
     * @param {File} file
     */
    function handleFile(file) {
        // Check file size (2GB limit)
        const maxSize = 2 * 1024 * 1024 * 1024;
        if (file.size > maxSize) {
            showAlert('File too large (max 2GB)', 'error');
            return;
        }

        currentFile = file;
        elements.fileName.textContent = file.name + ' (' + formatFileSize(file.size) + ')';
        elements.fileInfo.hidden = false;
        elements.encryptFileBtn.disabled = false;
        elements.decryptFileBtn.disabled = false;

        // Reset progress
        updateFileProgress(0);
    }

    /**
     * Format file size for display
     * @param {number} bytes
     * @returns {string}
     */
    function formatFileSize(bytes) {
        if (bytes === 0) return '0 B';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    /**
     * Update file progress bar
     * @param {number} percent
     */
    function updateFileProgress(percent) {
        if (percent > 0) {
            elements.fileProgress.classList.add('active');
        }
        elements.fileProgressFill.style.width = percent + '%';
        elements.fileProgressText.textContent = percent + '%';
        elements.fileProgressFill.parentElement.setAttribute('aria-valuenow', percent);
    }

    /**
     * Encrypt file
     */
    async function encryptFile() {
        if (!validatePassword()) return;
        if (!currentFile) {
            showAlert('Please select a file', 'error');
            return;
        }

        elements.encryptFileBtn.disabled = true;
        elements.decryptFileBtn.disabled = true;

        try {
            const password = elements.password.value;
            const blob = await SecureCrypto.encryptFile(password, currentFile, updateFileProgress);

            // Download the encrypted file
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = currentFile.name + '.encrypted';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);

            showAlert('File encrypted successfully!');
        } catch (err) {
            console.error('File encryption error:', err);
            showAlert('File encryption failed: ' + err.message, 'error');
        } finally {
            elements.encryptFileBtn.disabled = false;
            elements.decryptFileBtn.disabled = false;
            setTimeout(() => {
                elements.fileProgress.classList.remove('active');
            }, 1000);
        }
    }

    /**
     * Decrypt file
     */
    async function decryptFile() {
        const password = elements.password.value;
        if (!password) {
            showAlert('Please enter password', 'error');
            return;
        }
        if (!currentFile) {
            showAlert('Please select a file', 'error');
            return;
        }

        elements.encryptFileBtn.disabled = true;
        elements.decryptFileBtn.disabled = true;

        try {
            const result = await SecureCrypto.decryptFile(password, currentFile, updateFileProgress);

            // Download the decrypted file
            const url = URL.createObjectURL(result.blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = result.name;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);

            showAlert('File decrypted successfully!');
        } catch (err) {
            console.error('File decryption error:', err);
            showAlert('Decryption failed: Wrong password or corrupted file', 'error');
        } finally {
            elements.encryptFileBtn.disabled = false;
            elements.decryptFileBtn.disabled = false;
            setTimeout(() => {
                elements.fileProgress.classList.remove('active');
            }, 1000);
        }
    }

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Public API
    return {
        togglePassword,
        switchTab,
        encryptText,
        decryptText,
        copyResult,
        shareResult,
        downloadResult,
        encryptFile,
        decryptFile
    };
})();

// Register Service Worker for PWA
if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        navigator.serviceWorker.register('sw.js')
            .then(registration => {
                console.log('SW registered:', registration);
            })
            .catch(error => {
                console.log('SW registration failed:', error);
            });
    });
}
