# Secure Encryptor - Native Android App & Web PWA

A privacy-first encryption tool implementing AES-256-GCM encryption with PBKDF2 key derivation. Available as a modern **Native Android Application** (built with Kotlin & Jetpack Compose) and as a **Progressive Web App (PWA)**, with 100% cross-platform cryptographic interoperability.

---

## 📱 Android Application

### 📦 Pre-Built APK
You can install the latest pre-built Android APK directly:
- **Direct GitHub Release Download:** [SecureEncryptor.apk (v1.0.0)](https://github.com/nicolous260/SecureEncryptor/releases/download/v1.0.0/SecureEncryptor.apk)
- **Repository APK Path:** [`apk/SecureEncryptor.apk`](./apk/SecureEncryptor.apk)

### ✨ Features
- **AES-256-GCM Encryption**: Secure encryption for both plain text and files.
- **PBKDF2 Key Derivation**: 100,000 iterations using HMAC-SHA256 and a 16-byte random salt.
- **ENC2 Binary Specification**: Header layout compatible with both Android and Web versions:
  `[MAGIC (4 bytes "ENC2")][VERSION (1 byte)][SALT (16 bytes)][IV (12 bytes)][CIPHERTEXT]`
- **Password Generator & Strength Meter**: Real-time password strength evaluation and secure password generator.
- **Jetpack Compose & Material 3**: Sleek dark slate UI with smooth animated transitions between input and result states.
- **Edge-to-Edge Support**: Full Android 15 (SDK 35) edge-to-edge system bar integration.

### 🛠️ Building from Source

```bash
# Clone the repository
git clone https://github.com/nicolous260/SecureEncryptor.git
cd SecureEncryptor

# Build Debug APK
./gradlew assembleDebug

# Install on connected device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 Project Structure

```
SecureEncryptor/
├── apk/
│   └── SecureEncryptor.apk       # Pre-built Android APK
├── app/                          # Native Android Module
│   ├── src/main/java/com/secureencryptor/app/
│   │   ├── crypto/               # AES-GCM & PBKDF2 Crypto Engine
│   │   ├── ui/                   # Jetpack Compose UI, ViewModels, & Theme
│   │   └── MainActivity.kt       # Application Entry Point
│   └── build.gradle.kts
├── index.html                    # Web PWA HTML interface
├── js/                           # Web Crypto API JavaScript engine
├── css/                          # Web app styles
├── sw.js                         # Service worker for offline web support
├── build.gradle.kts              # Root Gradle build script
└── settings.gradle.kts           # Gradle settings
```

---

## 🌐 Web PWA Version

Open `index.html` in any modern web browser or run a local HTTP server:

```bash
# Run local web server
python3 -m http.server 8000
```

---

## 🔐 Security Specifications

- **Algorithm**: AES/GCM/NoPadding (256-bit key, 12-byte random IV)
- **Authentication Tag**: 128-bit GCM tag
- **Key Derivation**: PBKDF2WithHmacSHA256 (100,000 iterations, 16-byte random salt)
- **Header Magic**: `0x45 0x4E 0x43 0x32` (`"ENC2"`)
- **Zero Server Storage**: All operations are performed 100% on-device / client-side.

---

## 📄 License

MIT License - See LICENSE file for details.
