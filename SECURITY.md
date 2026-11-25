# Security Policy

## Supported Versions

Security fixes are applied to the latest release on the `master` branch. Please ensure you are running the latest version from [Google Play](https://play.google.com/store/apps/details?id=org.liberty.android.freeotpplus), [F-Droid](https://f-droid.org/packages/org.liberty.android.freeotpplus/), or [GitHub Releases](https://github.com/helloworld1/FreeOTPPlus/releases/latest) before reporting an issue.

| Version | Supported          |
| ------- | ------------------ |
| >= 3.7  | Yes                |
| < 3.7   | No                 |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues, discussions, or pull requests.**

Instead, please report vulnerabilities privately using [GitHub Private Vulnerability Reporting](https://github.com/helloworld1/FreeOTPPlus/security/advisories/new).

When submitting a report, please include:

- Affected FreeOTP+ version and Android OS version
- A clear description of the vulnerability and its security impact
- Step-by-step reproduction instructions or a minimal proof-of-concept (PoC)
- Any suggested remediation, if applicable

We will review reports on a best-effort basis, work on a fix if confirmed, and coordinate disclosure once a patched release is available.

## Security Architecture & Controls

### Authentication Modes & Token Secret Encryption

FreeOTP+ offers three user-selectable authentication modes:

- **None** *(default)*: Opens directly without an app-launch prompt; token secrets are stored in plaintext within the app-private `Room` database.
- **App launch**: Requires system biometric or device credential (`BIOMETRIC_WEAK | DEVICE_CREDENTIAL`) on app launch and after a 2-minute background timeout. If the device screen lock is later removed in Android Settings, FreeOTP+ fails closed (blocking unauthenticated access and exports until a screen lock is restored or `App launch` mode is explicitly turned off).
- **Token encrypted**: Encrypts each token secret at rest using **AES-256-GCM** (random 96-bit IV, 128-bit authentication tag) under a 256-bit key derived from a user-entered key (`>= 6` characters) via **PBKDF2-HMAC-SHA256** (150,000 iterations, 128-bit random salt). When strong biometrics (`BIOMETRIC_STRONG`) are enrolled, the derived key is wrapped with a hardware-backed `AndroidKeyStore` AES-256-GCM key (`setUserAuthenticationRequired(true)`, invalidated upon biometric enrollment changes) and unwrapped via `BiometricPrompt.CryptoObject` on launch, falling back to manual key entry when biometrics are unavailable or invalidated. Legacy plaintext `shared_prefs/tokens.xml` files are automatically purged upon migration and encryption setup.

### Session Timeout & Sensitive-Action Gating

- **Automatic Background Lock**: In **App launch** and **Token encrypted** modes, backgrounding the app starts a monotonic 2-minute timer (`SystemClock.elapsedRealtime()`). Upon expiration, FreeOTP+ zeroes the in-memory session key, clears owned clipboard entries, hides the navigation host, dismisses open dialogs and child screens, and re-verifies session state before processing pending document-picker or deep-link actions.
- **Sensitive-Action Authentication**: Revealing a token secret, opening **Share QR Code** / **Copy token URI**, and exporting backups (JSON, Key URI, or Token Images `.zip`) require device screen-lock authentication whenever a screen lock is configured (or encryption key verification in **Token encrypted** mode when no screen lock is set).

### Offline Enforcement, Screen, Clipboard & Memory Hygiene

- **OS-Enforced Offline Operation**: `AndroidManifest.xml` explicitly removes `INTERNET`, `ACCESS_NETWORK_STATE`, `READ_EXTERNAL_STORAGE`, and `WRITE_EXTERNAL_STORAGE` via `tools:node="remove"`. Only `CAMERA` (`android:required="false"`) is declared for QR scanning, and all file imports/exports use Android's Storage Access Framework (`DocumentContract`).
- **Screenshot & Recents Protection**: `WindowManager.LayoutParams.FLAG_SECURE` is enforced across `MainActivity`, all `DialogFragment`s, and all `AlertDialog`s in release builds.
- **Sensitive Clipboard Handling**: Copied OTP codes, revealed secrets, and token URIs are marked with `ClipDescription.EXTRA_IS_SENSITIVE` (hiding plaintext from Android 13+ clipboard previews), tracked by ownership so external clipboard contents are never overwritten, automatically cleared after 60 seconds, and immediately cleared when the session locks.
- **Autofill & Passphrase Scrubbing**: Platform autofill is disabled on encryption key dialogs (`IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`), and passphrases are read, UTF-8 encoded, and zero-scrubbed directly via `CharArray` / `CharBuffer` without creating immutable heap `String` copies.
- **Asset & Backup Confinement**: External QR codes and `otpauth://` deep links require user confirmation and strip untrusted `image=` parameters. Custom token images and `.zip` image archives are strictly confined to `context.filesDir` with bounded stream sizes, image header validation, and XXE-disabled SVG rendering. Android Auto Backup (`backup_rules.xml` / `data_extraction_rules.xml`) is scoped to `database`, `sharedpref`, and `file` domains, excludes legacy `tokens.xml` / `tokens.bak`, and requires client-side end-to-end encryption (`disableIfNoEncryptionCapabilities="true"` / `requireFlags="clientSideEncryption"`) for cloud backups on Android 9+ (API 28+).

## Scope

### In Scope

- Bypass of the **Authentication mode** (`App launch` or `Token encrypted`) lock gate, 2-minute session timeout, or sensitive-action authentication prompts (secret reveal, QR/URI share, or JSON / Key URI / Token Images `.zip` export) from an unprivileged context
- Cryptographic implementation flaws in **Token encrypted** mode (PBKDF2 key derivation, `AndroidKeyStore` biometric key wrapping, AES-256-GCM secret encryption, or encrypted backup export/import)
- Unauthorized cross-app access to OTP secrets, codes, or internal app files (including path traversal in custom image or `.zip` backup handling) on non-rooted Android devices
- Vulnerabilities in `otpauth://` URI handling, QR code scanning, SVG icon parsing, or FreeOTP+ JSON / Key URI / `.zip` / FreeOTP XML / Aegis JSON import parsing (e.g., XXE, unsafe deserialization, injection, spoofing, or silent state modification without user confirmation)
- Bypass of `FLAG_SECURE`, sensitive clipboard protections, or Android Auto Backup exclusion / client-side encryption rules, or unintended exposure of secrets in logs, broadcasts, or exported components

### Known Design Choices (Out of Scope)

The following behaviors are intentional design trade-offs for usability and interoperability and are not considered vulnerabilities on their own:

- **Rooted devices / physical debug access**: In **None** and **App launch** modes, FreeOTP+ relies on the Android OS application sandbox and device encryption to protect app-private storage (`Room` database). In **Token encrypted** mode, token secrets are encrypted at rest, but runtime memory inspection while unlocked, offline brute-force of weak user-chosen keys, or attacks requiring `root` privileges, unlocked bootloaders, custom ROMs, or authenticated `adb` access are out of scope.
- **Unencrypted token metadata**: In **Token encrypted** mode, only the OTP `secret` is encrypted; non-secret metadata (`issuer`, `label`, `categories`, `algorithm`, `digits`, `period`, `counter`, `imagePath`) remains unencrypted to support listing, searching, category filtering, sorting, and HOTP counter updates.
- **Backups, exports, and single-token sharing**:
  - In **None** and **App launch** modes, manual JSON / Key URI exports and Device-to-Device (D2D) transfers (as well as cloud backups on Android 7.0–8.1 devices prior to OS client-side backup encryption) store token secrets without app-layer encryption for cross-device and cross-app portability. On Android 9+ (API 28+), cloud backups run only when OS client-side backup encryption is enabled.
  - In **Token encrypted** mode, Android system backup stores encrypted token secrets (recoverable on a new device using the user's encryption key), and bulk JSON / Key URI exports let the user choose between **encrypted** (`freeotp-backup-encrypted_<datetime>.json` / `.txt`) and **decrypted** files.
  - Single-token sharing (**Share QR Code** and **Copy token URI**) outputs standard unencrypted `otpauth://` URIs after authentication so target services and authenticator apps can import them. Users remain responsible for securing decrypted backup files, shared QR codes, and copied URIs.
- **Optional authentication and encryption**: App-launch authentication and token secret encryption are opt-in in settings (`None` is the default); secret reveal, token sharing, and exports require authentication whenever a system screen lock is configured (or encryption key verification in **Token encrypted** mode when no screen lock is configured).
