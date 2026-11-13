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

### In Scope

- Bypass of the **Require authentication** (Biometric / PIN) lock screen or secret-reveal prompt from within an unprivileged context
- Unauthorized cross-app access to OTP secrets or codes on non-rooted Android devices
- Vulnerabilities in `otpauth://` URI handling, QR code scanning, or JSON / Key URI import parsing (e.g., injection, spoofing, or silent state modification without user confirmation)
- Unintended exposure of sensitive data in logs, broadcasts, or unprotected exported components

### Known Design Choices (Out of Scope)

The following behaviors are intentional design trade-offs for usability and interoperability and are not considered vulnerabilities on their own:

- **Rooted devices / physical debug access**: FreeOTP+ relies on the Android OS application sandbox and device encryption to protect app-private storage (`Room` database). Attacks requiring `root` privileges, unlocked bootloaders, custom ROMs, or authenticated `adb` access are out of scope.
- **Unencrypted backups and exports**: JSON and Key URI exports—as well as Android system backup (`allowBackup="true"`)—store token secrets without additional app-layer encryption so users can back up, restore, and migrate their tokens across devices and authenticator apps. Users are responsible for securing exported backup files.
- **Optional app-launch authentication**: App-launch authentication (`Require authentication`) is opt-in in settings; secret reveal, token sharing, and exports require authentication whenever a system screen lock is configured.
