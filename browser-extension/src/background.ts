import { NATIVE_HOST, type NativeRequest, type NativeResponse } from "./protocol";

declare const __KEYSCAN_BROWSER__: "chrome" | "edge" | "brave" | "firefox" | "safari";
const PAIRING_TOKEN_KEY = "keyscanPairingToken";

let nativePort: chrome.runtime.Port | null = null;
const pendingResponses = new Map<string, (response: NativeResponse) => void>();

function finishPending(error: NativeResponse["error"]): void {
  for (const [id, resolve] of pendingResponses) {
    resolve({ id, ok: false, error, diagnostic: chrome.runtime.lastError?.message });
    pendingResponses.delete(id);
  }
}

function bindNativePort(port: chrome.runtime.Port): void {
  port.onMessage.addListener((response: NativeResponse) => {
    if (!response || typeof response.id !== "string") return;
    const resolve = pendingResponses.get(response.id);
    if (!resolve) return;
    pendingResponses.delete(response.id);
    resolve(response);
  });
  port.onDisconnect.addListener(() => {
    nativePort = null;
    finishPending("HOST_NOT_FOUND");
  });
}

function getNativePort(): chrome.runtime.Port | null {
  if (nativePort) return nativePort;
  if (typeof chrome.runtime.connectNative !== "function") return null;
  try {
    nativePort = chrome.runtime.connectNative(NATIVE_HOST);
    bindNativePort(nativePort);
    return nativePort;
  } catch {
    nativePort = null;
    return null;
  }
}

function sendNative(message: NativePayload): Promise<NativeResponse> {
  const port = getNativePort();
  if (port) {
    return new Promise((resolve) => {
      pendingResponses.set(message.id, resolve);
      try {
        port.postMessage(message);
      } catch {
        pendingResponses.delete(message.id);
        resolve({ id: message.id, ok: false, error: "HOST_NOT_FOUND", diagnostic: chrome.runtime.lastError?.message });
      }
    });
  }
  return new Promise((resolve) => {
    chrome.runtime.sendNativeMessage(NATIVE_HOST, message, (response: NativeResponse | undefined) => {
      if (chrome.runtime.lastError || !response) {
        resolve({ id: message.id, ok: false, error: "HOST_NOT_FOUND", diagnostic: chrome.runtime.lastError?.message });
      } else {
        resolve(response);
      }
    });
  });
}

type NativePayload = Exclude<NativeRequest, { type: "openPopup" }>;

async function storedToken(): Promise<string | undefined> {
  // A freshly loaded unpacked extension can report no storage object for the
  // first service-worker turn. Treat that as an unpaired extension instead of
  // crashing before it can start the desktop pairing flow.
  const value: Record<string, unknown> = await chrome.storage.local.get(PAIRING_TOKEN_KEY).catch(() => ({}));
  if (!value) return undefined;
  return typeof value[PAIRING_TOKEN_KEY] === "string" ? value[PAIRING_TOKEN_KEY] : undefined;
}

async function sendPairedNative(message: NativePayload): Promise<NativeResponse> {
  const token = await storedToken();
  const payload = message.type === "register" ? { ...message, token } : token ? { ...message, token } : message;
  const response = await sendNative(payload as NativePayload);
  if (response.pairingToken) await chrome.storage.local.set({ [PAIRING_TOKEN_KEY]: response.pairingToken });
  if (response.error === "PAIRING_REQUIRED") {
    await chrome.storage.local.remove(PAIRING_TOKEN_KEY);
    // The first click must complete the consent flow and then resume the
    // original lookup/fill. Requiring a second click makes a paired extension
    // feel broken, especially after a desktop-side authorization reset.
    if (message.type !== "register") {
      const registration = await registerBrowser();
      if (registration.ok && registration.pairingToken) return sendPairedNative(message);
      return registration;
    }
  }
  return response;
}

async function registerBrowser(): Promise<NativeResponse> {
  return sendPairedNative({
    id: crypto.randomUUID(),
    type: "register",
    browser: __KEYSCAN_BROWSER__,
    extensionId: chrome.runtime.id,
    version: chrome.runtime.getManifest().version,
  });
}

async function heartbeat(): Promise<void> {
  const token = await storedToken();
  if (!token) return;
  const response = await sendNative({ id: crypto.randomUUID(), type: "heartbeat", token });
  if (response.error === "PAIRING_REQUIRED") await chrome.storage.local.remove(PAIRING_TOKEN_KEY);
}

const HEARTBEAT_ALARM = "keyscan-heartbeat";

/** An MV3 service worker is terminated when it goes idle, taking any setInterval with it. Only an
 *  alarm wakes the worker back up, so the desktop keeps seeing this browser as connected. The
 *  interval stays as a same-session fallback for a browser without the alarms API. */
function scheduleHeartbeat(): void {
  void registerBrowser();
  chrome.alarms?.create(HEARTBEAT_ALARM, { periodInMinutes: 0.5 });
}

chrome.alarms?.onAlarm.addListener((alarm) => { if (alarm.name === HEARTBEAT_ALARM) void heartbeat(); });
chrome.runtime.onStartup?.addListener(scheduleHeartbeat);
chrome.runtime.onInstalled?.addListener(scheduleHeartbeat);
scheduleHeartbeat();
if (typeof globalThis.setInterval === "function") globalThis.setInterval(() => { void heartbeat(); }, 30_000);

function canInjectContentScript(url: string | undefined): boolean {
  if (!url) return false;
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" || parsed.protocol === "http:";
  } catch {
    return false;
  }
}

async function ensureContentScript(tabId: number, url: string | undefined): Promise<void> {
  if (!canInjectContentScript(url)) return;
  if (typeof chrome.scripting?.executeScript !== "function") return;
  await chrome.scripting.executeScript({ target: { tabId }, files: ["content.js"] }).catch(() => undefined);
}

chrome.tabs?.onUpdated?.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status === "complete") void ensureContentScript(tabId, tab.url);
});

chrome.tabs?.onActivated?.addListener(({ tabId }) => {
  chrome.tabs.get(tabId, (tab) => {
    if (chrome.runtime.lastError) return;
    void ensureContentScript(tabId, tab.url);
  });
});

chrome.runtime.onMessage.addListener((message: NativeRequest | { type: "openPopup" }, sender, sendResponse) => {
  if (message?.type === "openPopup") {
    void (async () => {
      try {
        await chrome.action.openPopup();
        sendResponse({ ok: true });
      } catch {
        sendResponse({ ok: false });
      }
    })();
    return true;
  }
  if (!message || typeof (message as NativePayload).id !== "string") return false;
  void (async () => {
    const nativeMessage = message as NativePayload;
    if ("origin" in nativeMessage) {
      const actualOrigin = sender.tab?.url ? new URL(sender.tab.url).origin : "";
      const bridgeOrigin = originForNativeBridge(actualOrigin, nativeMessage.origin);
      if (!bridgeOrigin) {
        sendResponse({ id: nativeMessage.id, ok: false, error: "DENIED" } satisfies NativeResponse);
        return;
      }
      const response = await sendPairedNative({ ...nativeMessage, origin: bridgeOrigin } as NativePayload);
      sendResponse(response);
      return;
    }
    const response = await sendPairedNative(nativeMessage);
    sendResponse(response);
  })().catch(() => sendResponse({ id: nativeMessageId(message), ok: false, error: "INTERNAL" } satisfies NativeResponse));
  return true;
});

function nativeMessageId(message: NativeRequest | { type: "openPopup" }): string {
  return "id" in message ? message.id : "";
}

function originForNativeBridge(actualOrigin: string, requestedOrigin: string): string | null {
  if (actualOrigin.startsWith("https://") && actualOrigin === requestedOrigin) return requestedOrigin;
  if (actualOrigin !== requestedOrigin) return null;
  try {
    const url = new URL(actualOrigin);
    if (url.protocol !== "http:") return null;
    if (url.hostname !== "127.0.0.1" && url.hostname !== "localhost") return null;
    const host = url.hostname === "localhost" ? "localhost" : "127.0.0.1";
    return `https://${host}${url.port ? `:${url.port}` : ""}`;
  } catch {
    return null;
  }
}
