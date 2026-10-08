package com.secureencryptor.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secureencryptor.app.crypto.CryptoEngine
import com.secureencryptor.app.crypto.PasswordStrength
import com.secureencryptor.app.crypto.PasswordUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword: StateFlow<String> = _confirmPassword.asStateFlow()

    private val _isPasswordVisible = MutableStateFlow(false)
    val isPasswordVisible: StateFlow<Boolean> = _isPasswordVisible.asStateFlow()

    private val _passwordStrength = MutableStateFlow(PasswordUtils.checkPasswordStrength(""))
    val passwordStrength: StateFlow<PasswordStrength> = _passwordStrength.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: Text, 1: File
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _textResult = MutableStateFlow("")
    val textResult: StateFlow<String> = _textResult.asStateFlow()

    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _selectedFileSize = MutableStateFlow(0L)
    val selectedFileSize: StateFlow<Long> = _selectedFileSize.asStateFlow()

    private val _fileProgress = MutableStateFlow(0)
    val fileProgress: StateFlow<Int> = _fileProgress.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _alertMessage = MutableStateFlow<Pair<String, Boolean>?>(null) // Pair(message, isError)
    val alertMessage: StateFlow<Pair<String, Boolean>?> = _alertMessage.asStateFlow()

    private val _outputFileResult = MutableStateFlow<Pair<String, ByteArray>?>(null)
    val outputFileResult: StateFlow<Pair<String, ByteArray>?> = _outputFileResult.asStateFlow()

    fun onPasswordChanged(value: String) {
        _password.value = value
        _passwordStrength.value = PasswordUtils.checkPasswordStrength(value)
    }

    fun onConfirmPasswordChanged(value: String) {
        _confirmPassword.value = value
    }

    fun togglePasswordVisibility() {
        _isPasswordVisible.value = !_isPasswordVisible.value
    }

    fun generatePassword() {
        val generated = PasswordUtils.generatePassword(24)
        _password.value = generated
        _confirmPassword.value = generated
        _passwordStrength.value = PasswordUtils.checkPasswordStrength(generated)
        showAlert("Generated strong password!", isError = false)
    }

    fun onTabSelected(index: Int) {
        _selectedTab.value = index
    }

    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    fun onFileSelected(uri: Uri, fileName: String, fileSize: Long) {
        _selectedFileUri.value = uri
        _selectedFileName.value = fileName
        _selectedFileSize.value = fileSize
        _fileProgress.value = 0
    }

    fun clearSelectedFile() {
        _selectedFileUri.value = null
        _selectedFileName.value = null
        _selectedFileSize.value = 0L
        _fileProgress.value = 0
    }

    fun clearAlert() {
        _alertMessage.value = null
    }

    fun clearResult() {
        _textResult.value = ""
    }

    fun clearOutputFileResult() {
        _outputFileResult.value = null
    }

    private fun showAlert(message: String, isError: Boolean) {
        _alertMessage.value = Pair(message, isError)
    }

    private fun validatePassword(requireConfirm: Boolean = true): Boolean {
        val pass = _password.value
        val confirm = _confirmPassword.value

        if (pass.length < 6) {
            showAlert("Password must be at least 6 characters", isError = true)
            return false
        }
        if (requireConfirm && pass != confirm) {
            showAlert("Passwords do not match", isError = true)
            return false
        }
        return true
    }

    fun encryptText() {
        if (!validatePassword(requireConfirm = true)) return;
        val text = _inputText.value
        if (text.isEmpty()) {
            showAlert("Please enter text to encrypt", isError = true)
            return
        }

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val result = withContext(Dispatchers.Default) {
                    CryptoEngine.encryptText(_password.value, text)
                }
                _textResult.value = result
                showAlert("Text encrypted successfully!", isError = false)
            } catch (e: Exception) {
                showAlert("Encryption failed: ${e.localizedMessage}", isError = true)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun decryptText() {
        if (!validatePassword(requireConfirm = false)) return;
        val text = _inputText.value
        if (text.isEmpty()) {
            showAlert("Please enter encrypted text", isError = true)
            return
        }

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val result = withContext(Dispatchers.Default) {
                    CryptoEngine.decryptText(_password.value, text)
                }
                _textResult.value = result
                showAlert("Text decrypted successfully!", isError = false)
            } catch (e: Exception) {
                showAlert("Decryption failed: Wrong password or corrupted data", isError = true)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun encryptFile(context: Context) {
        if (!validatePassword(requireConfirm = true)) return
        val uri = _selectedFileUri.value ?: run {
            showAlert("Please select a file first", isError = true)
            return
        }
        val fileName = _selectedFileName.value ?: "file.dat"

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val fileBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalArgumentException("Failed to read file input stream")

                val encryptedBytes = withContext(Dispatchers.Default) {
                    CryptoEngine.encryptFile(_password.value, fileName, fileBytes) { progress ->
                        _fileProgress.value = progress
                    }
                }

                val outName = "$fileName.encrypted"
                _outputFileResult.value = Pair(outName, encryptedBytes)
                showAlert("File encrypted successfully! Ready to save.", isError = false)
            } catch (e: Exception) {
                showAlert("File encryption failed: ${e.localizedMessage}", isError = true)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun decryptFile(context: Context) {
        if (!validatePassword(requireConfirm = false)) return
        val uri = _selectedFileUri.value ?: run {
            showAlert("Please select a file first", isError = true)
            return
        }

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val fileBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalArgumentException("Failed to read file input stream")

                val decryptedFile = withContext(Dispatchers.Default) {
                    CryptoEngine.decryptFile(_password.value, fileBytes) { progress ->
                        _fileProgress.value = progress
                    }
                }

                _outputFileResult.value = Pair(decryptedFile.fileName, decryptedFile.data)
                showAlert("File decrypted successfully! Ready to save.", isError = false)
            } catch (e: Exception) {
                showAlert("Decryption failed: Wrong password or corrupted file", isError = true)
            } finally {
                _isProcessing.value = false
            }
        }
    }
}
