# KLIPSUP Security & Privacy Specification

## 1. API Key Storage & Redaction
- **Android Keystore Encryption**: User-supplied BYOK API keys are encrypted with AES-256-GCM using a hardware-backed `SecretKey` (`klipsup_byok_master_key_v1`) managed by `AndroidKeyStore` inside `CredentialVault`.
- **Zero Plaintext Persistence**: API keys are never written into `.klips.json` project files, exported archives, or unencrypted preferences.
- **Automated Secret Redaction**: `CredentialVault.redactSecrets()` strips configured keys as well as `sk-*`, `AIza*`, and `Bearer *` tokens before any message is written to `diagnostic_logs` or displayed in error banners.

## 2. Media Privacy & Offline First
- Core editing, rendering, color grading, audio DSP, 3D compositing, subtitle parsing, and On-Device Timeline Intelligence operate 100% offline without requiring a KLIPSUP account.
- External BYOK AI requests are only transmitted when explicitly triggered by the user with a configured provider key.
