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
- Argon2id key derivation (memory=16MB, iterations=2, parallelism=1)
- Biometric authentication before revealing secrets
- Audit logging for all security events

## ⚠️ CRITICAL: What Gemini MUST Review

**ONLY comment on these CRITICAL issues:**

1. **Crash risks** - Null pointer, type mismatch, array out of bounds
2. **Memory leaks** - Unclosed resources, listener leaks, unbounded collections
3. **Concurrency bugs** - Deadlock, race conditions, improper synchronization
4. **Logic bugs** - Wrong algorithm, incorrect conditionals, data flow errors
5. **Security vulnerabilities** - OWASP top 10 (injection, XSS, unsafe crypto)

## 🚫 FORBIDDEN: What Gemini MUST NOT Review

**DO NOT comment on these - they are handled by Android Lint:**

| Forbidden Topic | Why |
|-----------------|-----|
| Variable naming | Lint handles naming conventions |
| Code indentation | Lint handles formatting |
| Spacing/brackets | Lint handles style |
| Design pattern suggestions | Subjective - not critical |
| Syntax sugar preferences | Optional - not critical |
| Minor optimizations | Nitpicking - not critical |
| "Could be more readable" | Subjective opinion |
| "Consider refactoring" | Not a crash/security issue |

## Response Rules

- **Found critical issue?** → Comment with specific fix
- **No critical issues?** → Output `LGTM` only
- **Found style issue?** → Ignore it (Lint's job)
- **Found subjective suggestion?** → Ignore it (not critical)

## Example: Correct vs Wrong Review

### ✅ CORRECT (Critical Issue)
```
Line 45: Potential NullPointerException
- `credential.value` is used without null check
- If value is null, app will crash when decrypting
- Fix: Add null check or use requireNotNull()
```

### ❌ WRONG (Nitpick - IGNORE)
```
Line 45: Variable name `value` could be more descriptive
- Consider using `credentialValue` instead
```
**→ This comment should NOT be made. Naming is Lint's job.**

### ❌ WRONG (Subjective - IGNORE)
```
Line 50: This could be refactored into a separate utility function
- Would improve code organization
```
**→ This comment should NOT be made. Not a crash/security issue.**

## Code Style (Reference Only - Not for Review)

- Kotlin naming conventions
- Compose for UI, no XML layouts
- StateFlow for state management
- Coroutines for async operations
- Clean function names, no abbreviations

**Note: Style violations are caught by `./gradlew lintDebug`, not Gemini.**