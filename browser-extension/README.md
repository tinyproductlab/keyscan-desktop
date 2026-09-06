# KeyScan Browser Extension

## Build and verify

Run the TypeScript check, build all browser targets, and verify the generated
manifests:

```text
node node_modules/typescript/bin/tsc --noEmit
node scripts/clean.mjs
node scripts/build.mjs
node scripts/verify.mjs
```

The verifier requires Chrome, Edge, Brave, Firefox, and Safari outputs, 12 locale resources,
supported HTTPS/loopback HTTP content scripts, required native-messaging permissions, and
fill-time origin validation.

Create verified store-upload archives with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Package-Extensions.ps1
```

Chrome, Edge, Brave, and Safari produce ZIP files. Firefox produces an unsigned XPI for Mozilla
signing; the script checks required root entries, rejects development directories, and writes a
SHA-256 manifest. The Safari ZIP is a WebExtension resource package for Xcode import; final Safari
installation still requires a macOS Safari Web Extension containing app and signing.

## Firefox: unsigned package versus a damaged extension

`KeyScan-firefox-*.xpi` created locally is deliberately **unsigned**. Firefox Release rejects
unsigned XPIs and can report that they are damaged or corrupt. That message does not mean the
archive is a valid production install package.

For temporary developer testing, open `about:debugging#/runtime/this-firefox`, choose **Load
Temporary Add-on**, and select `dist/firefox/manifest.json`. Firefox removes a temporary add-on
on restart.

For a normal Firefox installation, submit the verified XPI to Mozilla Add-ons as an unlisted or
listed extension using the publisher's Mozilla account. Download and distribute only the XPI that
Mozilla signs. Do not ask users to disable Firefox signature enforcement.

Shared WebExtension companion for Chrome, Edge, Brave, Firefox, and Safari. Safari uses the same web source through an Xcode Safari Web Extension wrapper.

## Build

```powershell
npm install
npm run check
npm run build
```

Load the matching directory under `dist/` as an unpacked/temporary extension. For Safari, import or
convert `dist/safari` into an Xcode Safari Web Extension container before running it in Safari.

The extension never stores the vault, PIN, data protection key, or full credentials. It communicates with the unlocked KeyScan desktop app through the allow-listed native host `com.keyscan.desktop`.
