import { readFile, readdir, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const root = path.resolve(fileURLToPath(new URL("..", import.meta.url)));
const targets = ["chrome", "edge", "brave", "firefox", "safari"];
const locales = ["de", "en", "es", "fr", "it", "ja", "ko", "nl", "pt_BR", "ru", "zh_CN", "zh_TW"];
const requiredFiles = ["background.js", "content.js", "popup.js", "popup.html", "popup.css", "manifest.json"];

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

for (const locale of locales) {
  const messages = JSON.parse(await readFile(path.join(root, "public", "_locales", locale, "messages.json"), "utf8"));
  assert(messages.appName?.message === "KeyScan", locale + ": invalid appName");
  assert(typeof messages.appDescription?.message === "string" && messages.appDescription.message.trim(), locale + ": missing appDescription");
}
assert((await readdir(path.join(root, "public", "_locales"))).filter((name) => !name.startsWith(".")).length === locales.length, "Unexpected browser locale count");

for (const target of targets) {
  const out = path.join(root, "dist", target);
  for (const name of requiredFiles) assert((await stat(path.join(out, name))).size > 0, target + ": missing " + name);
  const manifest = JSON.parse(await readFile(path.join(out, "manifest.json"), "utf8"));
  assert(manifest.manifest_version === 3, target + ": Manifest V3 required");
  assert(manifest.name === "__MSG_appName__" && manifest.description === "__MSG_appDescription__", target + ": metadata must be localized");
  const permissions = manifest.permissions ?? [];
  for (const permission of ["activeTab", "storage", "nativeMessaging"]) {
    assert(permissions.includes(permission), target + ": missing required permission " + permission);
  }
  if (target !== "firefox") {
    assert(permissions.includes("scripting") && permissions.includes("tabs"), target + ": dynamic content-script reinjection requires scripting and tabs permissions");
    assert(JSON.stringify(manifest.host_permissions) === JSON.stringify(["https://*/*", "http://localhost/*", "http://127.0.0.1/*"]), target + ": host permissions must match supported HTTPS and loopback HTTP origins");
  }
  assert(JSON.stringify(manifest.content_scripts?.[0]?.matches) === JSON.stringify(["https://*/*", "http://localhost/*", "http://127.0.0.1/*"]), target + ": content script must match HTTPS and loopback HTTP pages");
  if (target === "firefox") {
    const gecko = manifest.browser_specific_settings?.gecko;
    assert(gecko?.strict_min_version === "140.0", "firefox: built-in data consent requires Firefox 140+");
    assert(JSON.stringify(gecko?.data_collection_permissions?.required) === JSON.stringify(["authenticationInfo", "websiteActivity"]), "firefox: required local bridge data categories are not declared");
    assert(manifest.browser_specific_settings?.gecko_android?.strict_min_version === "142.0", "firefox: Android consent schema boundary must be explicit");
  } else if (target === "safari") {
    assert(!manifest.key, "safari: Chromium extension key must not be copied into Safari manifest");
    assert(manifest.background?.service_worker === "background.js", "safari: Manifest V3 service worker is required");
  }
  const content = await readFile(path.join(out, "content.js"), "utf8");
  assert(content.includes("ORIGIN_CHANGED") && content.includes("location.origin"), target + ": missing fill-time origin validation");
  let listener;
  const sandbox = {
    chrome: { runtime: { onMessage: { addListener(value) { listener = value; } } } },
    document: { documentElement: { dataset: {} }, querySelectorAll() { return []; }, addEventListener() {} },
    location: { origin: "https://new.example" },
    MutationObserver: class { observe() {} },
  };
  vm.runInNewContext(content, sandbox, { filename: target + "/content.js" });
  let reply;
  listener({ type: "fillCredential", origin: "https://old.example", credential: { username: "alice", password: "secret" } }, {}, (value) => { reply = value; });
  assert(reply?.filled === false && reply?.error === "ORIGIN_CHANGED", target + ": navigation race was not rejected");

  const background = await readFile(path.join(out, "background.js"), "utf8");
  let backgroundListener;
  let nativeCalls = 0;
  let startupListener;
  let storedPairingToken;
  let lastNativeRequest;
  let requirePairingForNextLookup = true;
  const backgroundSandbox = {
    URL,
    crypto: { randomUUID() { return "verification-id"; } },
    chrome: {
      storage: { local: {
        async get() { return storedPairingToken ? { keyscanPairingToken: storedPairingToken } : {}; },
        async set(value) { storedPairingToken = value.keyscanPairingToken; },
        async remove() { storedPairingToken = undefined; },
      } },
      tabs: { async query() { return [{ url: "https://current.example/login" }]; } },
      runtime: {
        lastError: undefined,
        id: "abcdefghijklmnopabcdefghijklmnop",
        getManifest() { return { version: "0.1.0" }; },
        onStartup: { addListener(value) { startupListener = value; } },
        onMessage: { addListener(value) { backgroundListener = value; } },
        sendNativeMessage(_host, request, callback) {
          nativeCalls++; lastNativeRequest = request;
          if (request.type === "register") {
            callback({ id: request.id, ok: true, state: "paired", pairingToken: "paired-token-for-verification" });
          } else if (request.type === "findCredentials" && requirePairingForNextLookup) {
            requirePairingForNextLookup = false;
            callback({ id: request.id, ok: false, error: "PAIRING_REQUIRED" });
          } else {
            callback({ id: request.id, ok: true, state: "unlocked" });
          }
        },
      },
    },
  };
  vm.runInNewContext(background, backgroundSandbox, { filename: target + "/background.js" });
  startupListener();
  for (let tick = 0; tick < 10; tick++) await Promise.resolve();
  assert(storedPairingToken === "paired-token-for-verification", target + ": startup registration did not store pairing token");
  assert(lastNativeRequest?.type === "register" && lastNativeRequest?.browser === target, target + ": registration did not identify its browser target");
  const registrationCalls = nativeCalls;
  const sender = { tab: { url: "https://current.example/login" } };
  const mismatch = await new Promise((resolve) => backgroundListener({ id: "mismatch", type: "findCredentials", origin: "https://other.example" }, sender, resolve));
  assert(mismatch?.error === "DENIED" && nativeCalls === registrationCalls, target + ": mismatched active-tab origin reached native host");
  storedPairingToken = undefined;
  const matching = await new Promise((resolve) => backgroundListener({ id: "matching", type: "findCredentials", origin: "https://current.example" }, sender, resolve));
  assert(matching?.ok === true && nativeCalls === registrationCalls + 3 && lastNativeRequest?.token === storedPairingToken, target + ": first lookup did not pair and resume with its token");
}

// Chrome Web Store will not publish an item whose manifest has no 128px icon, and without
// action.default_icon the toolbar falls back to a generic puzzle piece.
for (const target of targets) {
  const manifest = JSON.parse(await readFile(path.join(root, "dist", target, "manifest.json"), "utf8"));
  for (const [label, icons] of [["icons", manifest.icons], ["action.default_icon", manifest.action?.default_icon]]) {
    assert(icons && icons["128"], target + ": " + label + " is missing a 128px entry");
    for (const [size, file] of Object.entries(icons)) {
      const stats = await stat(path.join(root, "dist", target, file)).catch(() => null);
      assert(stats?.isFile(), target + ": " + label + " " + size + " points at a missing file: " + file);
    }
  }
}

// The popup ships its own inline string table, so _locales cannot catch a language that
// is missing an entry. A missing key silently falls back to English mid-popup.
{
  const popupSource = await readFile(path.join(root, "src", "popup.ts"), "utf8");
  const table = popupSource.match(/const strings = \{([\s\S]*?)\n\};/);
  assert(table, "popup.ts: could not locate the inline string table");
  const entries = [...table[1].matchAll(/\n {2}("?[A-Za-z-]+"?): \{([\s\S]*?)\},?(?=\n {2}"?[A-Za-z-]+"?: \{|\s*$)/g)]
    .map(([, name, body]) => [name.replace(/"/g, ""), new Set([...body.matchAll(/(\w+):/g)].map((m) => m[1]))]);
  const byLanguage = new Map(entries);
  const english = byLanguage.get("en");
  assert(english, "popup.ts: the inline string table has no English entry");
  assert(byLanguage.size >= 12, "popup.ts: expected at least 12 popup languages, found " + byLanguage.size);
  for (const [language, keys] of byLanguage) {
    const missing = [...english].filter((key) => !keys.has(key));
    assert(missing.length === 0, "popup.ts: " + language + " is missing " + missing.join(", "));
  }
  // "free of charge", not "liberty". Only the CJK entries have ever got this wrong.
  const taglines = [...popupSource.matchAll(/tagline: "([^"]*)"/g)].map((m) => m[1]);
  const libre = taglines.filter((value) => /自由|자유/.test(value));
  assert(libre.length === 0, "popup.ts: tagline uses the 'liberty' sense of free: " + libre.join(", "));
}

console.log("Browser extension verification passed: " + targets.length + " targets, " + locales.length + " locales, HTTPS and loopback HTTP fill.");
