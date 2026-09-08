# Real browser validation

Validated on Windows on 2026-09-07 using isolated, disposable browser profiles. The smoke test
does not read or modify the user's normal browser profile and removes its temporary profile after
the browser process exits.

## Results

- Microsoft Edge 152.0.4191.66 loaded `dist/edge` successfully.
  Development extension ID: `ccehabgiddlgfkhgkmpdehddiekngjel`.
- **Native-messaging is no longer pending.** With the host installed for that development ID, Edge
  loaded the extension, its background script called `register`, and the desktop bridge received
  the request with the right browser, extension ID and version. Approving it issued a pairing
  token, and the desktop recorded the pairing in `%APPDATA%\KeyScan\browser-plugins.properties`
  with the token held in the DPAPI secret store rather than in that file.
- The desktop pairing dialog itself was exercised: Edge loading the extension raised
  **Allow browser extension to connect?** in KeyScan with the right browser, extension ID and
  version, and pressing **Allow** issued the token and moved the home card to **Connected: Edge**.
- The heartbeat was watched for three and a half minutes after pairing. `lastSeenAt` advances every
  30 seconds, which keeps the card inside the desktop's 90-second liveness window. Before the
  alarms fix below it stalled the moment the service worker went idle, and the card fell back to
  **Connected: none** permanently.
- Driving `KeyScanNativeHost.exe` directly over stdio framing confirmed the fail-closed paths:
  an unapproved `register` returns `PAIRING_DENIED` after the 60-second approval timeout, and a
  `status` call without a token returns `PAIRING_REQUIRED`.
- Google Chrome 152.0.7977.76 ignores command-line unpacked extension loading in the official
  branded build. Chrome still requires a manual Developer mode load or Chrome for Testing; this
  is not recorded as a passed Chrome runtime test.
- Brave 152.1.94.121 and Firefox 153.0.4 are installed. The locally packaged XPI is unsigned and
  Firefox Release will reject it; temporary runtime loading uses `about:debugging` and production
  installation requires Mozilla signing.

Development IDs are path-derived and are not release store IDs. They must not be copied into the
production native-host allow-list.

## Reproduce the native-messaging round trip

1. `./gradlew :native-host:packageWindowsAppImage` in `desktop/`.
2. `scripts/Test-ChromiumLoad.ps1 -Target edge -BrowserExecutable <msedge.exe>` to read the
   development extension ID.
3. `packaging/windows/native-messaging/Install-KeyScanNativeHost.ps1` with that ID.
4. Start KeyScan desktop, then launch Edge with `--load-extension=dist\edge` and approve the
   pairing prompt.
5. Uninstall afterwards with `Uninstall-KeyScanNativeHost.ps1`; the development ID must not be
   left in the allow-list.

## Firefox temporary validation

1. Open `about:debugging#/runtime/this-firefox` in Firefox.
2. Select **Load Temporary Add-on** and choose `dist/firefox/manifest.json`.
3. Open an HTTPS login or registration page and confirm the KeyScan popup can connect to an
   unlocked desktop vault.

Do not double-click the locally built `.xpi` in Firefox Release. Its rejection as unsigned is
expected. Mozilla must sign the uploaded release XPI before end users can install it normally.

Development IDs are path-derived and are not release store IDs. They must not be copied into the
production native-host allow-list.

## Reproduce Chromium isolation test

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-ChromiumLoad.ps1 `
  -Target edge -BrowserExecutable 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
```
