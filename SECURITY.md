# Security Policy

## Supported Versions

Security fixes are applied to the latest release on the `master` branch. Please ensure you are running the latest version from [Google Play](https://play.google.com/store/apps/details?id=org.liberty.android.freeotpplus), [F-Droid](https://f-droid.org/packages/org.liberty.android.freeotpplus/), or [GitHub Releases](https://github.com/helloworld1/FreeOTPPlus/releases/latest) before reporting an issue.

| Version | Supported          |
| ------- | ------------------ |
| >= 3.5  | Yes                |
| < 3.5   | No                 |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull requests.**

Instead, please report vulnerabilities privately using [GitHub Private Vulnerability Reporting](https://github.com/helloworld1/FreeOTPPlus/security/advisories/new).

When submitting a report, please include:

- Affected FreeOTP+ version and Android OS version
- A clear description of the vulnerability and its security impact
- Step-by-step reproduction instructions or a minimal proof-of-concept (PoC)
- Any suggested remediation, if applicable

We will review reports on a best-effort basis, work on a fix if confirmed, and coordinate disclosure once a patched release is available.

## Security Model & Scope

### Authentication Modes & Token Secret Encryption

FreeOTP+ offers three user-selectable authentication modes:

- **No encryption** *(default)*: Opens directly without an app-launch prompt; token secrets are stored in plaintext within the app-private `Room` database.
- **App launch**: Requires system biometric or device credential (`BIOMETRIC_WEAK | DEVICE_CREDENTIAL`) on app launch and after a 2-minute background timeout; token secrets remain stored in plaintext within the app-private `Room` database.
- **Token encrypted**: Encrypts each token secret at rest using **AES-256-GCM** (random 96-bit IV, 128-bit authentication tag) under a 256-bit key derived from a user-entered key (`>= 6` characters) via **PBKDF2-HMAC-SHA256** (150,000 iterations, 128-bit random salt). When strong biometrics (`BIOMETRIC_STRONG`) are enrolled, the derived key is wrapped with an `AndroidKeyStore` AES-256-GCM key (`setUserAuthenticationRequired(true)`, invalidated upon biometric enrollment changes) and unwrapped via `BiometricPrompt.CryptoObject` on launch, falling back to manual key entry when biometrics are unavailable or invalidated.

### In Scope

- Bypass of the **Authentication mode** (`App launch` or `Token encrypted`) lock screen, or bypass of secret-reveal, token-share, or export authentication prompts from within an unprivileged context
- Cryptographic implementation flaws in **Token encrypted** mode (key derivation, `AndroidKeyStore` biometric key wrapping, AES-256-GCM token secret encryption, or encrypted backup export/import)
- Unauthorized cross-app access to OTP secrets or codes on non-rooted Android devices
- Vulnerabilities in `otpauth://` URI handling, QR code scanning, or JSON / Key URI import parsing (e.g., injection, spoofing, or silent state modification without user confirmation)
- Unintended exposure of sensitive data in logs, broadcasts, or unprotected exported components

### Known Design Choices (Out of Scope)

The following behaviors are intentional design trade-offs for usability and interoperability and are not considered vulnerabilities on their own:

- **Rooted devices / physical debug access**: In **No encryption** and **App launch** modes, FreeOTP+ relies on the Android OS application sandbox and device encryption to protect app-private storage (`Room` database). In **Token encrypted** mode, token secrets are encrypted at rest, but runtime memory inspection while unlocked, offline brute-force of weak user-chosen keys, or attacks requiring `root` privileges, unlocked bootloaders, custom ROMs, or authenticated `adb` access are out of scope.
- **Unencrypted token metadata**: In **Token encrypted** mode, only the OTP `secret` is encrypted; non-secret metadata (`issuer`, `label`, `category`, `algorithm`, `digits`, `period`, `counter`, `imagePath`) remains unencrypted to support listing, searching, category filtering, and HOTP counter updates.
- **Backups, exports, and single-token sharing**:
  - In **No encryption** and **App launch** modes, JSON / Key URI exports and Android system backup (`allowBackup="true"`) store token secrets without app-layer encryption for cross-device and cross-app portability.
  - In **Token encrypted** mode, Android system backup stores encrypted token secrets (recoverable on a new device using the user's encryption key), and bulk JSON / Key URI exports let the user choose between **encrypted** (`freeotp-backup-encrypted_<datetime>.json` / `.txt`) and **decrypted** files.
  - Single-token sharing (`Share QR Code` and `Share to other apps`) always outputs unencrypted `otpauth://` URIs after authentication so target services and authenticator apps can scan them. Users remain responsible for securing decrypted backup files and shared QR codes.
- **Optional authentication and encryption**: App-launch authentication and token secret encryption are opt-in in settings (`No encryption` is the default); secret reveal, token sharing, and exports require authentication whenever a system screen lock is configured (or encryption key verification in **Token encrypted** mode when no screen lock is configured).
