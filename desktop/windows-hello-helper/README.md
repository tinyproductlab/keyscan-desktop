# Windows Hello desktop interop helper

This small Windows-only executable calls the official desktop interop API
`IUserConsentVerifierInterop::RequestVerificationForWindowAsync`. It requires the active KeyScan
window's HWND, so the Windows Hello dialog is owned by the correct desktop window.

It does **not** derive or replace KeyScan's Android-compatible PIN/data-protection root key and does
not store either secret. Quick unlock uses a high-protection, non-exportable RSA key in the Microsoft
Passport Key Storage Provider. Its in-memory signature derives a separate database-key envelope;
the original PIN/data-key envelope remains unchanged for recovery.

Build on Windows with:

```powershell
powershell -ExecutionPolicy Bypass -File .\windows-hello-helper\Build-WindowsHelloHelper.ps1
```

`check` is non-interactive. `verify <HWND> <message>` displays Windows Hello and must only be invoked
in response to a visible user action.

The application also uses `keycheck`, `enroll`, `sign`, and `delete-key`. These commands accept only
a fixed-format key name, require a non-zero HWND for private-key operations, and emit one strict
machine-readable stdout record. They are internal protocols, not user-facing CLI commands.
