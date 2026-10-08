# 🔒 Secure Encryptor

[![Android](https://img.shields.io/badge/Android-SDK%2035-brightgreen?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-purple?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-blue?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Release](https://img.shields.io/badge/Release-v1.0.0-emerald)](https://github.com/nicolous260/SecureEncryptor/releases/tag/v1.0.0)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

A privacy-first, client-side encryption application implementing military-grade **AES-256-GCM** encryption and **PBKDF2-HMAC-SHA256** key derivation.

Available as a modern **Native Android Application** (built with Kotlin & Jetpack Compose) and a **Progressive Web App (PWA)**, featuring 100% cross-platform cryptographic interoperability (`ENC2` binary format).

---

## 📲 Quick Download

| Platform | Download Link | Description |
| :--- | :--- | :--- |
| **Android (APK)** | [**SecureEncryptor.apk (v1.0.0)**](https://github.com/nicolous260/SecureEncryptor/releases/download/v1.0.0/SecureEncryptor.apk) | Native Android App (SDK 24+) |
| **GitHub Release** | [**View Release Page**](https://github.com/nicolous260/SecureEncryptor/releases/tag/v1.0.0) | v1.0.0 Tagged Release |
| **Web Version** | [`index.html`](./index.html) | Progressive Web App (PWA) |

---

## ✨ Features

- **AES-256-GCM Symmetric Cipher**: Authenticated encryption providing confidentiality and integrity verification.
- **PBKDF2 Key Stretching**: 100,000 iterations using HMAC-SHA256 and a 16-byte random salt to prevent brute-force attacks.
- **Cross-Platform ENC2 Binary Specification**: Data encrypted on Android can be decrypted on the Web PWA, and vice versa.
- **Text & File Support**: Encrypt raw text to Base64 payloads or process full files with embedded filename headers.
- **Real-Time Password Strength Meter**: Evaluates password entropy with animated strength progress indicators.
- **Secure Password Generator**: Generates cryptographically secure 24-character random passwords.
- **Modern Jetpack Compose UI**: Slate dark theme, Segmented Control tabs, and smooth animated transitions between Input and Result views.
- **Edge-to-Edge Design**: Full support for Android 15 edge-to-edge system insets.
- **Zero Server Footprint**: 100% on-device processing. No data ever leaves your device.

---

## 🛠️ Building & Installation

### Requirements
- JDK 17 or JDK 21
- Android SDK 35 (Build-Tools 35.0.0)

### Quick Build Commands

```bash
# Clone repository
git clone https://github.com/nicolous260/SecureEncryptor.git
cd SecureEncryptor

# Build Debug APK using Gradle Wrapper
./gradlew assembleDebug

# Install APK on connected device / emulator via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📂 Project Structure

```
SecureEncryptor/
├── apk/
│   └── SecureEncryptor.apk       # Compiled Android APK (v1.0.0)
├── app/                          # Native Android Module
│   ├── src/main/java/com/secureencryptor/app/
│   │   ├── crypto/               # CryptoEngine & PasswordUtils
│   │   ├── ui/                   # MainScreen, ViewModel, & Theme
│   │   └── MainActivity.kt       # Application Entry Point
│   ├── src/test/                 # Unit & Interoperability Tests
│   └── build.gradle.kts
├── docs/
│   └── ARCHITECTURE.md           # Technical Architecture & Spec Doc
├── index.html                    # Web PWA HTML interface
├── js/                           # Web Crypto API JavaScript engine
├── css/                          # Web app styles
├── sw.js                         # Service worker for offline web support
├── build.gradle.kts              # Root Gradle build script
└── settings.gradle.kts           # Gradle settings
```

---

## 📖 Architecture & Security Details

For detailed technical specifications, binary header layouts, and security guarantees, see the [**Architecture Documentation**](./docs/ARCHITECTURE.md).

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
