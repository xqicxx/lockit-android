# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Lockit is a secure credential manager Android app with a Rust CLI companion. It follows the "Technical Brutalism" design system — 0px rounded corners, 1px black borders, JetBrains Mono + Inter fonts, industrial orange/red accent colors.

## Tech Stack

- **Language**: Kotlin 17
- **UI**: Jetpack Compose
- **Storage**: Room (SQLite) with AES-256-GCM encrypted credential values
- **Encryption**: AES-256-GCM + Argon2id key derivation (BouncyCastle)
- **Architecture**: MVVM with Kotlin Flow
- **Build**: Gradle (AGP + Kotlin plugin + KSP)

## Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device/emulator
./gradlew installDebug

# Build + install
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# Clean build
./gradlew clean assembleDebug

# Run tests (if any)
./gradlew testDebugUnitTest
./gradlew connectedAndroidTest
```

## Architecture

### Data Layer (`com.lockit.data.*`)
- `database/` — Room: `CredentialEntity`, `CredentialDao`, `LockitDatabase` (singleton)
- `crypto/` — `LockitCrypto` (AES-256-GCM encrypt/decrypt), `KeyManager` (SharedPreferences salt/hash storage)
- `vault/` — `VaultManager`: business logic, combines DAO + crypto + audit logging
- `audit/` — `AuditLogger`: tracks all security events (unlock, create, edit, delete, copy, view)

### Domain Layer (`com.lockit.domain.*`)
- `Credential` — domain model with id, name, type, service, key, value, metadata, timestamps
- `CredentialType` — enum with 18 types (ApiKey, Account, Phone, BankCard, Email, Token, etc.), each defines its own form fields with labels, placeholders, dropdown presets, and required field indices

### App Entry (`com.lockit.LockitApp`)
Application-level DI: lazy-initialized `database`, `keyManager`, `auditLogger`, `vaultManager`.

### UI Layer (`com.lockit.ui.*`)
- `MainActivity.kt` — `MainFlow` composable manages screen state (`AppScreen` enum). All screens are in-app navigation (no NavController).
- `components/` — Brutalist design system: `BrutalistTopBar`, `BrutalistButton`, `BrutalistTextField`, `BrutalistCard`, `BrutalistConfirmDialog`, `BrutalistBottomNav`, `CredentialFormComponents` (DropdownWithCustomInput, CredentialTypeDropdown, SelectionChip, PRESET_*)
- `screens/` — 8 screens: VaultUnlock, VaultExplorer, SecretDetails, AddCredential, EditCredential, Repos, Logs, Config

### Navigation Pattern
All screens rendered via `when(currentScreen)` in `MainScaffold`. State is managed through `mutableStateOf` in `MainFlow`. Callbacks like `onCredentialSelected`, `onCredentialEdit` flow up to `MainFlow`.

### Credential Type System
Each `CredentialType` has a `fields: List<CredentialField>` that defines:
- `label` — display label for the form field
- `placeholder` — hint text
- `isDropdown` — whether to use dropdown with presets
- `presets` — list of preset values for dropdowns
- `requiredFieldIndices` — which field indices are required for validation

The form dynamically renders fields based on the selected type.

### Design Tokens (`com.lockit.ui.theme`)
- Colors: `Primary` (black), `White`, `IndustrialOrange` (#B34700), `TacticalRed` (#A30000), `SurfaceLow`, `SurfaceHighest`
- Typography: `JetBrainsMonoFamily`, `InterFamily`
- Shapes: `RectangleShape` (0px corners)

## Key Constraints
- Encryption format must be compatible with the Rust CLI (`lockit/crypto.rs`)
- Database structure: name, service, key, value (encrypted), metadata (JSON) — avoid schema migrations
- All UI uses 0px rounded corners, 1px borders, 2px offset shadows
- Requires font files in `res/font/`: `inter_regular.ttf`, `inter_bold.ttf`, `inter_extrabold.ttf`, `jetbrains_mono_regular.ttf`, `jetbrains_mono_medium.ttf`
