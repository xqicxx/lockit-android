# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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


<claude-mem-context>
# Memory Context

# [lockit-android] recent context, 2026-04-29 2:55am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 36 obs (10,373t read) | 90,062t work | 88% savings

### Apr 29, 2026
36 2:33a 🟣 Modifying ChatGPT panel UI in programming plan board
37 " 🔵 Codebase exploration reveals existing expiry infrastructure for ChatGPT panel
38 2:34a 🟣 Added TDD test for parseRemainingDays in ChatGPTCodingPlan
39 " 🔵 Gradle build blocked by file permission on gradle-wrapper.zip.lck
40 2:35a 🔵 RED phase confirmed: test compilation fails as expected for unimplemented method
41 " ✅ ChatGPT coding plan board showing additional quota/account info
42 " ✅ Plan updated: enhanced ChatGPT parsing added as explicit step
43 2:36a 🟣 Implemented ChatGPTCodingPlan parseRemainingDays and enhanced account parsing
44 " 🟣 UI modifications: removed x/100 counter, right-aligned percentage, added remaining days
45 " 🟣 All changes compiles and tests pass — GREEN phase successful
46 2:37a 🟣 Final verification: debug APK builds successfully with all changes
47 " 🟣 Complete implementation summary across 3 files — ChatGPT panel UI refresh
48 " 🟣 All 5 plan steps completed — ChatGPT panel UI modification finalized
49 " 🔄 Compact provider row now shows email local-part instead of raw truncated email
50 2:38a 🟣 Added TDD test enforcing account info timeout ≤ 2 seconds
51 2:39a 🔄 Extracted named timeout constants with tighter budgets across ChatGPT API calls
52 " 🟣 Second RED-GREEN cycle complete — timeout budget test passes
53 2:40a 🔄 Reduced Usage API log verbosity — only logs response size, not full body
54 2:42a 🔵 Dark mode issue identified for night-time usage
55 " 🔵 Dark color scheme inspected in Theme.kt
56 " 🔴 Dark mode gauge progress bar visibility fixed
57 2:43a ✅ Provider name updated for Qwen/Bailian
58 2:49a 🟣 CodingPlanRefreshPolicy test file created
59 " 🟣 CodingPlanRefreshPolicy implementation created
60 2:50a 🔄 Startup prefetch throttled with 30-minute refresh policy
61 " 🔄 Duplicate vault-unlock quota refresh removed
62 2:51a 🔄 ReposScreen refresh logic unified with 30-minute throttling policy
63 " 🟣 Periodic 30-minute auto-refresh for Coding Plan board
64 2:52a 🔄 Reactive PrefetchState subscription added to ReposViewModel
65 " 🔄 Coding Plan refresh unified under centralized refresh policy
66 " 🔵 Build failure from over-aggressive import removal in MainActivity
67 " 🔴 Fixed missing kotlinx.coroutines.launch import in MainActivity
68 2:53a 🟣 CodingPlanRefreshPolicy tests pass — TDD green phase complete
69 " 🔄 Debug APK builds successfully after all refresh refactoring
70 " 🔄 Complete CodingPlan refresh pipeline consolidated via git diff
71 2:55a ⚖️ Startup prefetch will be exempted from 30-minute throttling

Access 90k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>