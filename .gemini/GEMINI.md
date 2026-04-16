# Lockit Android - Gemini Project Instructions

## Project Overview

Lockit is a secure credential manager Android app using Technical Brutalism design.

## Design Constraints

- **0px rounded corners** - Always use RectangleShape
- **1px black borders** - All interactive elements
- **2px offset shadows** - Industrial aesthetic
- **Colors**: Black (#000000), Industrial Orange (#B34700), Tactical Red (#A30000)
- **Fonts**: JetBrains Mono (data), Inter (UI)

## Security Requirements

- AES-256-GCM encryption for credential values
- Argon2id key derivation (memory=64MB, iterations=3, parallelism=4)
- Biometric authentication before revealing secrets
- Audit logging for all security events

## Code Style

- Kotlin naming conventions
- Compose for UI, no XML layouts
- StateFlow for state management
- Coroutines for async operations
- Clean function names, no abbreviations

## What to Review

1. Security vulnerabilities (OWASP top 10)
2. Performance issues (unnecessary recomposition, memory leaks)
3. Code quality (duplicate code, parameter sprawl)
4. Best practices (Compose patterns, Kotlin idioms)
5. Design consistency (brutalist constraints)