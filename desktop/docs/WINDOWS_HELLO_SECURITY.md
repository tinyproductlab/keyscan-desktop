# Windows Hello security boundary

KeyScan's Android-compatible root key is still derived only from the user's PIN and data protection
key. Windows Hello must not alter that KDF or any secure-backup format.

The current Windows helper uses Microsoft's desktop API
`IUserConsentVerifierInterop::RequestVerificationForWindowAsync`, supplies the active application's
HWND, and reports only a fixed availability or verification result. It never receives the KeyScan
PIN, data protection key, database key, or vault data.

`UserConsentVerifier` proves that Windows completed device authentication for a visible operation;
it does not itself encrypt or release the KeyScan database key. KeyScan therefore does not use
ordinary current-user DPAPI as the Windows Hello database-key wrapper.

Quick unlock creates a non-exportable RSA key in the Microsoft Passport Key Storage Provider with
`NCRYPT_UI_PROTECT_KEY_FLAG | NCRYPT_UI_FORCE_HIGH_PROTECTION_FLAG` and attaches the active KeyScan
HWND through `NCRYPT_WINDOW_HANDLE_PROPERTY`. A random 32-byte local challenge is signed using
deterministic RSA PKCS#1 v1.5 with SHA-256. The signature is used only in memory as input to the
AES-GCM database-key envelope and is never stored. Reproducing the signature therefore requires the
Passport private-key operation and its Windows Hello confirmation. The original Android-compatible
PIN/data-key envelope remains unchanged and is always the recovery/fallback path.

The envelope and wrong-signature behavior have automated tests. This development machine reports
`DeviceNotPresent`, so registration, signing after reboot, cancellation, Windows Hello key removal,
and fallback behavior still require a configured physical Windows Hello device before the overall
Windows Hello task can be marked complete.
