# Safari target

The shared TypeScript, HTML, CSS and locale resources now build into `dist/safari`.

This directory is not the installable Safari extension itself. Safari requires an Xcode Safari Web
Extension containing app. Import or convert `dist/safari` into that Xcode target on macOS, then sign
and run the containing app.

The containing macOS app must communicate with KeyScan through a Safari native-messaging handler
and App Group / local bridge, then preserve the same request schema and security boundaries
described in `docs/NATIVE_MESSAGING.md`.

Current status:

- WebExtension resources: implemented in `dist/safari`.
- Shared JavaScript protocol: reports browser target `safari`.
- Desktop protocol: accepts browser target `safari`.
- Final Safari containing app / signing / App Store packaging: still requires Xcode work on macOS.
