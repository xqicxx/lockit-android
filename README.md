# 🔐 Lockit Android

A secure credential manager for Android with **Technical Brutalism** design philosophy.

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## Features

### 🔑 Credential Management
- **18 credential types**: API Key, Account, Phone, Bank Card, Email, Password, Token, SSH Key, Bearer Token, Basic Auth, Webhook Secret, OAuth Client, AWS Credential, GPG Key, Database URL, ID Card, Note, Coding Plan
- **Dynamic forms**: Each type has custom fields with dropdown presets and validation
- **Chip-based selection**: Quick selection for common services (Google, GitHub, WeChat, Alipay, etc.)

### 🔒 Security
- **AES-256-GCM encryption** for all credential values
- **Argon2id** key derivation (memory=64MB, iterations=3, parallelism=4)
- **Biometric authentication** for revealing sensitive values
- **15-minute session cache** for convenient access
- **Audit logging** for all security events

### 📋 Copy Features
- **Long-press single value**: Copy specific field directly
- **Long-press card**: Copy full JSON structured data
- **Tap to reveal**: Show/hide sensitive values with eye icon
- **Copy button**: Quick copy for revealed values

### 🎨 Design
- **0px rounded corners** - Pure rectangular shapes
- **1px black borders** - Sharp, defined edges
- **2px offset shadows** - Industrial aesthetic
- **JetBrains Mono** - Monospace for data display
- **Inter** - Clean UI typography
- **Industrial Orange** (#B34700) - Accent color
- **Tactical Red** (#A30000) - Warning/delete actions

### 📊 Coding Plan Board
- Real-time quota display from Alibaba Bailian
- Prefetch on app startup for instant display
- Supports multiple providers (Qwen, OpenAI, Anthropic, etc.)

## Screens

| Screen | Description |
|--------|-------------|
| **Vault Unlock** | Master password entry / vault creation |
| **Vault Explorer** | Credential list + search + reveal actions |
| **Repos** | Service groupings + Coding Plan Board |
| **Secret Details** | Full credential details + copy/reveal/delete |
| **Add Credential** | Dynamic form based on credential type |
| **Edit Credential** | Modify existing credentials |
| **Logs** | Security audit trail |
| **Config** | Vault info + lock + settings |

## Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Kotlin 17 |
| UI | Jetpack Compose |
| Database | Room (SQLite) |
| Encryption | AES-256-GCM + Argon2id (BouncyCastle) |
| Architecture | MVVM with StateFlow |
| Build | Gradle (AGP + Kotlin Plugin + KSP) |

## Setup

### 1. Clone & Open

```bash
git clone https://github.com/xqicxx/lockit-android.git
cd lockit-android
```

Open in Android Studio: `File → Open → select lockit-android/`

### 2. Build

```bash
./gradlew assembleDebug
```

### 3. Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or use Android Studio's Run button.

## Project Structure

```
app/src/main/java/com/lockit/
├── LockitApp.kt          # Application DI container
├── data/
│   ├── audit/            # Audit logging
│   ├── biometric/        # PIN + biometric storage
│   ├── crypto/           # AES-256-GCM encryption
│   ├── database/         # Room DAO + entities
│   ├── sync/             # Google Drive sync (future)
│   └── vault/            # VaultManager + CodingPlanPrefs
├── domain/
│   ├── model/            # Credential + CredentialType
│   └── qwen/             # Bailian API integration
├── ui/
│   ├── components/       # Brutalist UI components
│   ├── screens/          # All screens (8 total)
│   └── theme/            # Colors, typography, shapes
└── utils/                # BiometricUtils, parsers
```

## Credential Types

| Type | Use Case |
|------|----------|
| `API_KEY` | AI services, cloud providers, REST APIs |
| `ACCOUNT` | Website/app login credentials |
| `PASSWORD` | Standalone passwords (WiFi, shared) |
| `PHONE` | Phone numbers with region codes |
| `BANK_CARD` | Payment card details |
| `EMAIL` | Email accounts + SMTP passwords |
| `TOKEN` | Bearer/session/auth tokens |
| `SSH_KEY` | SSH private keys |
| `CODING_PLAN` | AI coding agent tokens |

## Encryption Format

Compatible with Rust CLI (`lockit/crypto.rs`):

```
[12-byte nonce][ciphertext + 16-byte GCM tag]
```

Key derivation: Argon2id (memory=64MB, iterations=3, parallelism=4)

## License

MIT License - See [LICENSE](LICENSE) for details.

## Contributing

PRs welcome! Please follow the Technical Brutalism design guidelines:
- No rounded corners (always 0px)
- 1px borders on all interactive elements
- JetBrains Mono for data, Inter for UI text