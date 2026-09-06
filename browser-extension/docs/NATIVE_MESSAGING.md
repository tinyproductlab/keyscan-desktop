# Native messaging contract

Host name: `com.keyscan.desktop`

Messages use browser native-messaging framing: a 32-bit little-endian byte length followed by one UTF-8 JSON object.

Initial request types:

- `status`: returns whether the desktop vault is locked.
- `findCredentials`: accepts an exact HTTPS origin and returns metadata only (`id`, `label`, `username`). Password material requires a later explicit credential-selection request and desktop-side authorization.
- `requestCredential`: accepts the same exact HTTPS origin plus one returned credential ID. The
  desktop app displays the site and account, waits up to 60 seconds for explicit approval, and then
  releases only that username/password pair. Locking, denial, timeout, origin mismatch and a second
  concurrent request all fail closed.

Security rules:

- Allow only the release extension IDs for Chrome, Edge, Brave, Firefox and Safari.
- Never accept a caller-provided executable path or vault path.
- Validate request schema, size and origin on the desktop side.
- Reject frames larger than 64 KiB, unknown JSON fields and non-HTTPS/non-origin URLs.
- Do not return the whole vault or encryption keys.
- Keep authorization short-lived and clear secrets after filling.
- Never automatically submit a form.
- Fill only visible, editable username/password fields after approval; the user reviews and submits.
- Re-check the live document origin at the exact fill step. If the tab navigated while desktop
  approval was pending, discard the released credential instead of filling the new page.
- Content scripts run on HTTPS pages and loopback HTTP development pages only; non-loopback HTTP
  origins are normalized away before reaching the native bridge.
- Firefox declares the required `authenticationInfo` and `websiteActivity` data categories
  because the add-on exchanges the active origin and selected credential with the user's local
  KeyScan desktop process. Firefox 140 or newer supplies the built-in consent experience.
  The Gecko Android schema boundary is declared as 142, but mobile Firefox is not a supported
  target because it cannot connect to the Windows/macOS native host.
- Safari uses the same WebExtension JavaScript and manifest target, but its final native bridge
  must be implemented inside the macOS Safari Web Extension containing app. The JavaScript side
  still talks through `runtime.sendNativeMessage`; the app-side handler must attach the trusted
  native caller and keep the same schema, pairing and response-size rules.

The shared desktop protocol implementation and tests live in
`shared-core/.../nativebridge/NativeMessagingProtocol.kt`. The Windows browser-launched executable
uses a loopback-only IPC endpoint and a per-desktop-session random token protected for the current
Windows user with DPAPI. Release extension IDs and the browser native-host manifests must still be
installed before release; no development wildcard is permitted.
