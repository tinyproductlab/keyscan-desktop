# KeyScan Desktop

Source for the **free** KeyScan desktop app (Windows and macOS) and its browser
extension. The phone apps are sold separately; everything in this repository is
free to use and to build yourself.

| Platform | Where it lives | Cost |
| --- | --- | --- |
| Windows, macOS | this repository | free, no Pro tier |
| Browser extension | this repository (`browser-extension/`) | free |
| Android | [keyscan-android](https://github.com/tinyproductlab/keyscan-android) | paid |
| iOS | App Store | paid |

Website: <https://keyscan.tinylabpro.com>

## What it does

- **Password ledger** — sites, usernames, passwords, with history
- **TOTP authenticator** — time-based codes generated locally
- **Secure vault** — identity, finance, and other sensitive records
- **Random password generator**
- **Share centre** — turn text or a link into a QR code, and send files or text
  to another device over the local network with a one-time password and an
  expiry
- **Data management** — encrypted local backups and WebDAV sync
- **Browser extension** — fills credentials only after you approve each request
  in the desktop app, and only for the site you are on

There is no account. Nothing is uploaded unless you configure WebDAV yourself.

## Security model

- The vault is encrypted with AES-GCM. The key is derived from a PIN plus a data
  encryption key that only you hold — neither can be recovered for you.
- Backups are encrypted before they are written or uploaded.
- The browser extension can never read the vault directly. It asks the desktop
  app, the desktop app asks you, and it returns nothing while the vault is
  locked.
- Local network sharing serves a single file over HTTP on your LAN, guarded by a
  6-digit password and an expiry of 5, 10, or 30 minutes.

Format details are in [`desktop/docs`](desktop/docs): `LOCAL_VAULT_FORMAT.md`,
`SECURE_BACKUP_FORMAT.md`, `SECURITY_COMPATIBILITY.md`, and
`WINDOWS_HELLO_SECURITY.md`.

## Build

### Desktop (Kotlin, Compose Multiplatform)

Gradle provisions the JDK 17 toolchain itself, so a JDK on `PATH` is enough to
start.

```bash
cd desktop
./gradlew :desktop-ui:run              # run it
./gradlew :desktop-ui:classes          # compile only
./gradlew packageDistributionForCurrentOS   # MSI/EXE on Windows, DMG on macOS
```

### Browser extension (TypeScript)

```bash
cd browser-extension
npm install
node node_modules/typescript/bin/tsc --noEmit
node scripts/build.mjs                 # chrome, edge, brave, firefox, safari
node scripts/verify.mjs
```

## Checks

Two audits run over this repository and both are meant to stay green:

```powershell
powershell -File desktop/tools/Audit-DesktopLocalization.ps1
```

Fails on hardcoded Chinese in a UI source file, on a catalog key missing from any
of the 12 languages, on a placeholder count that differs from English, and on
code that would surface a raw exception message to the user.

```bash
node browser-extension/scripts/verify.mjs
```

Checks all five browser targets, the 12 popup languages, the content-script
origin rules, and the native-messaging permissions.

## Contributing

Issues and pull requests are welcome. Please run both checks above before opening
a pull request, and keep new user-visible strings in the catalogs rather than in
the source.

Security reports: see [SECURITY.md](SECURITY.md).

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free for noncommercial use. Commercial
use needs written permission from the copyright holder.
