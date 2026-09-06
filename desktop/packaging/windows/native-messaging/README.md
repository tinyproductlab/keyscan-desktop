# KeyScan Windows native messaging host

Build the self-contained host executable first:

```powershell
.\gradlew.bat :native-host:packageWindowsAppImage
```

After Chrome, Edge and Brave have assigned their final 32-character extension IDs, register the
host for the current Windows user. Do not use development IDs in a release installer.

```powershell
.\packaging\windows\native-messaging\Install-KeyScanNativeHost.ps1 `
  -HostExecutable '.\native-host\build\windows-app-image\KeyScanNativeHost\KeyScanNativeHost.exe' `
  -ChromeExtensionId '<chrome-store-id>' `
  -EdgeExtensionId '<edge-store-id>' `
  -BraveExtensionId '<brave-extension-id>' `
  -FirefoxExtensionId 'keyscan@keyscan.app'
```

The script writes separate Chromium and Firefox manifests, creates the host's defense-in-depth
caller allow-list, and registers HKCU discovery keys for Google Chrome, Microsoft Edge, Brave and
Mozilla Firefox. `-SkipRegistry` generates and validates files without changing the registry.

To remove the registry entries and generated manifests:

```powershell
.\packaging\windows\native-messaging\Uninstall-KeyScanNativeHost.ps1
```

The final Windows installer must copy the complete `KeyScanNativeHost` app-image directory, not
only the `.exe`, because the adjacent runtime and application JARs are required.
