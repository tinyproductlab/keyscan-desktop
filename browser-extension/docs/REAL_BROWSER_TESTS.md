# Real browser validation

Validated on Windows on 2026-07-30 using isolated, disposable browser profiles. The smoke test
does not read or modify the user's normal browser profile and removes its temporary profile after
the browser process exits.

## Results

- Microsoft Edge 150.0.4078.105 loaded `dist/edge` successfully.
  Development extension ID: `gjkkjedmgnliacnnoecijlmfidkbkfnb`.
- Brave 150.1.92.144 loaded `dist/brave` successfully.
  Development extension ID: `mjhdlmdmapjelnlcdhnhnkjeglpigpon`.
- Google Chrome 150.0.7871.187 ignores command-line unpacked extension loading in the official
  branded build. Chrome still requires a manual Developer mode load or Chrome for Testing; this
  is not recorded as a passed Chrome runtime test.
- Firefox 153.0.1 is installed. Mozilla `web-ext lint` reports 0 errors, 0 notices, and 0 warnings
  for `dist/firefox`. The locally packaged XPI is unsigned and Firefox Release will reject it;
  temporary runtime loading uses `about:debugging` and production installation requires Mozilla
  signing. Native-host messaging remains pending.

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
