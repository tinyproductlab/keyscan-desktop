import type { NativeResponse } from "./protocol";

const passwordSelector = 'input[type="password"]';
const launcherSize = 32;
const launcherGap = 6;
const menuWidth = 292;
const sessionTtlMs = 3 * 60 * 1000;
const browserWindow = globalThis.window as Window | undefined;
const keyscanRuntime = globalThis as typeof globalThis & { __KEYSCAN_CONTENT_READY__?: boolean };

keyscanRuntime.__KEYSCAN_CONTENT_READY__ = true;
if (document.documentElement) {
  document.documentElement.dataset.keyscanContent = "ready";
  document.documentElement.dataset.keyscanContentVersion = "0.1.0";
}

type CredentialSummary = NonNullable<NativeResponse["credentials"]>[number];
type Scenario = "login" | "signup" | "change_password" | "unknown";
type PageInfo = { origin: string; passwordFields: number; scenario: Scenario };
type GeneratedPasswordSession = {
  origin: string;
  username: string;
  password: string;
  scenario: "signup" | "change_password";
  createdAt: number;
};
type SubmittedCredential = {
  title: string;
  username: string;
  password: string;
  mode: "save" | "update";
  credentialId?: string;
};
type FilledCredentialSession = {
  origin: string;
  id: string;
  username: string;
  createdAt: number;
};

let launcherHost: HTMLDivElement | null = null;
let launcherTarget: HTMLInputElement | null = null;
let launcherMenu: HTMLDivElement | null = null;
let launcherScheduled = false;
let currentPageInfo: PageInfo = { origin: location.origin, passwordFields: 0, scenario: "unknown" };
let cachedOrigin = "";
let cachedStatus: NativeResponse | null = null;
let cachedCredentials: CredentialSummary[] = [];
let cacheState: "idle" | "loading" | "ready" | "error" = "idle";
let cachePromise: Promise<void> | null = null;
let generatorPrompt: HTMLDivElement | null = null;
let generatorPromptShownFor: HTMLInputElement | null = null;
let savePrompt: HTMLDivElement | null = null;
let generatedSession: GeneratedPasswordSession | null = null;
let filledCredentialSession: FilledCredentialSession | null = null;

function isSupportedOrigin(origin: string): boolean {
  if (origin.startsWith("https://")) return true;
  try {
    const value = new URL(origin);
    return value.protocol === "http:" && (value.hostname === "127.0.0.1" || value.hostname === "localhost");
  } catch {
    return false;
  }
}

function isVisibleField(field: HTMLInputElement): boolean {
  return !field.disabled && !field.readOnly && field.offsetParent !== null && field.getClientRects().length > 0;
}

function visiblePasswordFields(scope: ParentNode = document): HTMLInputElement[] {
  return Array.from(scope.querySelectorAll<HTMLInputElement>(passwordSelector)).filter(isVisibleField);
}

function visibleTextFields(scope: ParentNode = document): HTMLInputElement[] {
  return Array.from(scope.querySelectorAll<HTMLInputElement>('input[autocomplete="username"],input[type="email"],input[type="text"]'))
    .filter(isVisibleField)
    .filter((field) => !/search|captcha|code|otp|totp/i.test(`${field.name} ${field.id} ${field.autocomplete}`));
}

function fieldText(field: HTMLInputElement): string {
  return `${field.name} ${field.id} ${field.placeholder} ${field.autocomplete} ${field.getAttribute("aria-label") ?? ""}`.toLowerCase();
}

function setValue(field: HTMLInputElement, value: string): void {
  field.focus();
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
  setter?.call(field, value);
  field.dispatchEvent(new Event("input", { bubbles: true }));
  field.dispatchEvent(new Event("change", { bubbles: true }));
  field.dispatchEvent(new Event("blur", { bubbles: true }));
}

function formScope(field: HTMLInputElement): ParentNode {
  return field.form ?? field.closest("form") ?? document;
}

function detectScenario(scope: ParentNode = document): Scenario {
  const passwords = visiblePasswordFields(scope);
  if (!passwords.length) return "unknown";

  const hasCurrent = passwords.some((field) => field.autocomplete === "current-password" || /old|current|原密码|旧密码/.test(fieldText(field)));
  const hasNew = passwords.some((field) => field.autocomplete === "new-password" || /new|confirm|again|repeat|确认|重复|新密码/.test(fieldText(field)));

  if (hasCurrent && (hasNew || passwords.length >= 2)) return "change_password";
  if (hasNew || passwords.length >= 2) return "signup";
  return "login";
}

function targetScenario(field: HTMLInputElement): Scenario {
  return detectScenario(formScope(field));
}

function isNewPasswordField(field: HTMLInputElement, passwords: HTMLInputElement[], scenario: Scenario): boolean {
  const text = fieldText(field);
  if (field.autocomplete === "new-password") return true;
  if (/new|confirm|again|repeat|确认|重复|新密码/.test(text)) return true;
  if (scenario === "signup" && passwords.length >= 2) return true;
  if (scenario === "change_password" && passwords.length >= 2) return passwords.indexOf(field) > 0;
  return false;
}

function getUsernameNear(password: HTMLInputElement): string {
  const scope = formScope(password);
  const username = visibleTextFields(scope)
    .filter((field) => Boolean(field.compareDocumentPosition(password) & Node.DOCUMENT_POSITION_FOLLOWING))
    .pop();
  return username?.value.trim() || "";
}

function fillCredentialLocally(credential: { username: string; password: string }): boolean {
  const password = visiblePasswordFields()[0];
  if (!password) return false;
  const username = visibleTextFields(formScope(password))
    .filter((field) => Boolean(field.compareDocumentPosition(password) & Node.DOCUMENT_POSITION_FOLLOWING))
    .pop();
  if (username) setValue(username, credential.username);
  setValue(password, credential.password);
  password.focus();
  return true;
}

function fillNewPasswordFields(password: string, sourceField?: HTMLInputElement): boolean {
  const scope = sourceField ? formScope(sourceField) : document;
  const fields = visiblePasswordFields(scope);
  const scenario = sourceField ? targetScenario(sourceField) : detectScenario(scope);
  const targets = fields.filter((field) => isNewPasswordField(field, fields, scenario));
  if (!targets.length) return false;
  for (const field of targets) setValue(field, password);
  targets[0].focus();
  rememberGeneratedPassword(password, scenario === "change_password" ? "change_password" : "signup", sourceField ?? targets[0]);
  return true;
}

function rememberGeneratedPassword(password: string, scenario: "signup" | "change_password", field: HTMLInputElement): void {
  generatedSession = {
    origin: location.origin,
    username: getUsernameNear(field),
    password,
    scenario,
    createdAt: Date.now(),
  };
}

function validGeneratedPassword(username: string, scenario: "signup" | "change_password", password: string): string | null {
  if (!generatedSession) return null;
  if (generatedSession.origin !== location.origin) return null;
  if (generatedSession.scenario !== scenario) return null;
  if (Date.now() - generatedSession.createdAt > sessionTtlMs) return null;
  if (generatedSession.username && username && generatedSession.username !== username) return null;
  if (generatedSession.password && generatedSession.password !== password) return null;
  return generatedSession.password;
}

function getLauncherTarget(): HTMLInputElement | null {
  const active = document.activeElement;
  if (active instanceof HTMLInputElement && active.matches(passwordSelector) && isVisibleField(active)) return active;
  return visiblePasswordFields()[0] ?? null;
}

function hasLauncherUi(): boolean {
  return typeof browserWindow?.requestAnimationFrame === "function" && typeof document.documentElement?.append === "function";
}

function createIcon(): SVGSVGElement {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  svg.style.width = "17px";
  svg.style.height = "17px";
  svg.style.display = "block";
  svg.innerHTML = `
    <path d="M7.75 8.5a4.25 4.25 0 1 1 8.5 0 4.25 4.25 0 0 1-8.5 0Z" fill="none" stroke="currentColor" stroke-width="1.8"/>
    <path d="M12 12.75v3.1" fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="1.8"/>
    <path d="M15.8 16.55h2.1l1.8 1.8" fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="1.8"/>
    <path d="M17.55 18.3h1.6l1.1 1.1" fill="none" stroke="currentColor" stroke-linecap="round" stroke-width="1.8"/>
  `;
  return svg;
}

function ensureLauncher(): HTMLDivElement {
  if (launcherHost?.isConnected) return launcherHost;

  launcherHost = document.createElement("div");
  launcherHost.dataset.keyscanLauncher = "true";
  launcherHost.style.position = "fixed";
  launcherHost.style.left = "0";
  launcherHost.style.top = "0";
  launcherHost.style.width = `${launcherSize}px`;
  launcherHost.style.height = `${launcherSize}px`;
  launcherHost.style.zIndex = "2147483647";
  launcherHost.style.pointerEvents = "none";
  launcherHost.style.display = "none";

  const shadow = launcherHost.attachShadow({ mode: "open" });
  const style = document.createElement("style");
  style.textContent = `
    :host { all: initial; }
    button {
      all: unset;
      box-sizing: border-box;
      display: grid;
      place-items: center;
      width: ${launcherSize}px;
      height: ${launcherSize}px;
      border-radius: 11px;
      cursor: pointer;
      pointer-events: auto;
      color: #ffffff;
      background: linear-gradient(135deg, #20b982 0%, #3979ed 100%);
      box-shadow: 0 8px 24px rgba(16, 33, 64, 0.24);
      border: 1px solid rgba(255, 255, 255, 0.42);
    }
  `;
  const button = document.createElement("button");
  button.type = "button";
  button.title = "KeyScan";
  button.setAttribute("aria-label", "Open KeyScan");
  button.append(createIcon());
  button.addEventListener("click", (event) => {
    event.preventDefault();
    event.stopPropagation();
    void openQuickMenu();
  });
  shadow.append(style, button);
  document.documentElement.append(launcherHost);
  return launcherHost;
}

function hideMenu(): void {
  launcherMenu?.remove();
  launcherMenu = null;
}

function hideGeneratorPrompt(): void {
  generatorPrompt?.remove();
  generatorPrompt = null;
}

function hideLauncher(): void {
  if (!launcherHost) return;
  launcherHost.style.display = "none";
  launcherTarget = null;
  hideMenu();
}

function positionLauncher(field: HTMLInputElement): void {
  const host = ensureLauncher();
  const rect = field.getBoundingClientRect();
  const viewportWidth = browserWindow?.innerWidth ?? 0;
  const viewportHeight = browserWindow?.innerHeight ?? 0;
  if (rect.width < 64 || rect.height < 20 || rect.bottom < 0 || rect.right < 0 || rect.left > viewportWidth || rect.top > viewportHeight) {
    hideLauncher();
    return;
  }
  const insideLeft = Math.max(8, rect.right - launcherSize - 8);
  const outsideLeft = Math.min(viewportWidth - launcherSize - 8, rect.right + launcherGap);
  const left = rect.width >= 96 ? insideLeft : outsideLeft;
  const top = Math.max(8, Math.min(viewportHeight - launcherSize - 8, rect.top + (rect.height - launcherSize) / 2));
  host.style.display = "block";
  host.style.left = `${Math.round(left)}px`;
  host.style.top = `${Math.round(top)}px`;
  launcherTarget = field;
}

function positionMenu(menu: HTMLDivElement, field: HTMLInputElement): void {
  const rect = field.getBoundingClientRect();
  const viewportWidth = browserWindow?.innerWidth ?? 0;
  const viewportHeight = browserWindow?.innerHeight ?? 0;
  const fitsRight = rect.right + launcherGap + menuWidth <= viewportWidth - 8;
  const left = fitsRight ? rect.right + launcherGap : Math.max(8, rect.left - menuWidth - launcherGap);
  const top = Math.max(8, Math.min(viewportHeight - 24, rect.top - 8));
  menu.style.left = `${Math.round(left)}px`;
  menu.style.top = `${Math.round(top)}px`;
}

function createPanel(): HTMLDivElement {
  const panel = document.createElement("div");
  panel.style.position = "fixed";
  panel.style.zIndex = "2147483647";
  panel.style.width = `${menuWidth}px`;
  panel.style.boxSizing = "border-box";
  panel.style.padding = "12px";
  panel.style.borderRadius = "17px";
  panel.style.border = "1px solid rgba(96, 132, 176, 0.28)";
  panel.style.background = "rgba(255,255,255,0.98)";
  panel.style.backdropFilter = "blur(10px)";
  panel.style.boxShadow = "0 16px 40px rgba(16, 33, 64, 0.22)";
  panel.style.color = "#17365d";
  panel.style.font = '13px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif';
  panel.style.pointerEvents = "auto";
  return panel;
}

function heading(text: string): HTMLDivElement {
  const node = document.createElement("div");
  node.textContent = text;
  node.style.fontSize = "15px";
  node.style.fontWeight = "800";
  return node;
}

function caption(text: string): HTMLDivElement {
  const node = document.createElement("div");
  node.textContent = text;
  node.style.marginTop = "4px";
  node.style.color = "#5d7290";
  node.style.fontSize = "12px";
  return node;
}

function button(label: string, onClick: () => void | Promise<void>, secondary = false): HTMLButtonElement {
  const node = document.createElement("button");
  node.type = "button";
  node.textContent = label;
  node.style.width = "100%";
  node.style.border = "0";
  node.style.borderRadius = "12px";
  node.style.padding = "10px 12px";
  node.style.marginTop = "9px";
  node.style.textAlign = "left";
  node.style.cursor = "pointer";
  node.style.fontWeight = secondary ? "600" : "750";
  node.style.background = secondary ? "#f3f7ff" : "linear-gradient(135deg, #20b982 0%, #3979ed 100%)";
  node.style.color = secondary ? "#17365d" : "#ffffff";
  node.addEventListener("click", async (event) => {
    event.preventDefault();
    event.stopPropagation();
    await onClick();
  });
  return node;
}

async function sendRuntimeMessage<T>(message: unknown): Promise<T | undefined> {
  try {
    return await chrome.runtime.sendMessage(message) as T;
  } catch {
    return undefined;
  }
}

async function warmNativeCache(pageInfo: PageInfo): Promise<void> {
  if (!isSupportedOrigin(pageInfo.origin)) return;
  if (cacheState === "loading" && cachedOrigin === pageInfo.origin && cachePromise) return cachePromise;
  if (cacheState === "ready" && cachedOrigin === pageInfo.origin) return;

  cachedOrigin = pageInfo.origin;
  cacheState = "loading";
  cachePromise = (async () => {
    const [status, found] = await Promise.all([
      sendRuntimeMessage<NativeResponse>({ id: crypto.randomUUID(), type: "status" }),
      sendRuntimeMessage<NativeResponse>({ id: crypto.randomUUID(), type: "findCredentials", origin: pageInfo.origin }),
    ]);
    cachedStatus = status ?? null;
    cachedCredentials = found?.ok ? found.credentials ?? [] : [];
    cacheState = cachedStatus?.ok ? "ready" : "error";
    refreshLauncher();
  })().catch(() => {
    cachedStatus = null;
    cachedCredentials = [];
    cacheState = "error";
    refreshLauncher();
  });
  return cachePromise;
}

async function requestDesktopGeneratedPassword(): Promise<string | null> {
  const response = await sendRuntimeMessage<NativeResponse>({
    id: crypto.randomUUID(),
    type: "generatePassword",
    origin: location.origin,
    length: 16,
    mode: "recommended",
  });
  return response?.ok && typeof response.generatedPassword === "string" ? response.generatedPassword : null;
}

async function requestDesktopUnlock(): Promise<{ ok: boolean; error?: NativeResponse["error"] }> {
  const result = await sendRuntimeMessage<NativeResponse>({ id: crypto.randomUUID(), type: "unlock" });
  return { ok: Boolean(result?.ok), error: result?.error };
}

async function releaseAndFillCredential(credential: CredentialSummary): Promise<void> {
  const released = await sendRuntimeMessage<NativeResponse>({
    id: crypto.randomUUID(),
    type: "requestCredential",
    origin: location.origin,
    credentialId: credential.id,
  });
  if (!released?.ok || !released.credential) return;
  if (fillCredentialLocally(released.credential)) {
    filledCredentialSession = {
      origin: location.origin,
      id: credential.id,
      username: released.credential.username || credential.username,
      createdAt: Date.now(),
    };
    hideMenu();
  }
}

async function generateAndFill(field: HTMLInputElement): Promise<void> {
  const password = await requestDesktopGeneratedPassword();
  if (!password) return;
  if (fillNewPasswordFields(password, field)) {
    hideMenu();
    hideGeneratorPrompt();
  }
}

async function openQuickMenu(): Promise<void> {
  if (!launcherTarget) return;
  await warmNativeCache(currentPageInfo);
  const scenario = targetScenario(launcherTarget);

  if (cachedStatus?.state === "locked") {
    hideMenu();
    const panel = createPanel();
    launcherMenu = panel;
    panel.append(heading("KeyScan 已锁定"), caption("请先在桌面端解锁，然后再填充或保存密码。"));
    const status = caption("等待桌面端解锁…");
    panel.append(button("在 KeyScan 中解锁", async () => {
      const result = await requestDesktopUnlock();
      status.textContent = result.ok ? "已解锁" : result.error === "DENIED" ? "解锁已取消" : "无法连接桌面端";
      if (result.ok) {
        cacheState = "idle";
        await warmNativeCache(currentPageInfo);
        await openQuickMenu();
      }
    }));
    panel.append(status);
    document.documentElement.append(panel);
    positionMenu(panel, launcherTarget);
    return;
  }

  if (scenario === "signup" || scenario === "change_password") {
    hideMenu();
    const panel = createPanel();
    launcherMenu = panel;
    panel.append(
      heading(scenario === "change_password" ? "更新密码" : "注册新账号"),
      caption("KeyScan 生成强密码，并自动填入新密码和确认密码。提交后会提示保存。"),
    );
    panel.append(button("使用随机密码", () => generateAndFill(launcherTarget!)));
    if (cachedCredentials.length) {
      panel.append(caption("也可以选择已保存账号："));
      for (const item of cachedCredentials.slice(0, 4)) {
        panel.append(button(`${item.label} · ${item.username}`, () => releaseAndFillCredential(item), true));
      }
    }
    document.documentElement.append(panel);
    positionMenu(panel, launcherTarget);
    return;
  }

  hideMenu();
  const panel = createPanel();
  launcherMenu = panel;
  panel.append(heading("KeyScan"), caption(cachedCredentials.length ? "选择账号填充到当前页面。" : "当前网站还没有保存账号。"));
  for (const item of cachedCredentials.slice(0, 8)) {
    panel.append(button(`${item.label} · ${item.username}`, () => releaseAndFillCredential(item), true));
  }
  if (!cachedCredentials.length) panel.append(button(cacheState === "loading" ? "正在连接 KeyScan…" : "未找到可用账号", () => undefined, true));
  document.documentElement.append(panel);
  positionMenu(panel, launcherTarget);
}

function maybeShowGeneratorPrompt(field: HTMLInputElement, force = false): void {
  const scenario = targetScenario(field);
  if (!isSupportedOrigin(location.origin) || (scenario !== "signup" && scenario !== "change_password")) return;
  if (!force && generatorPromptShownFor === field) return;
  generatorPromptShownFor = field;
  hideGeneratorPrompt();
  const panel = createPanel();
  generatorPrompt = panel;
  panel.append(
    heading("使用随机密码？"),
    caption("KeyScan 会生成并填入新密码/确认密码，提交后再保存到密码账本。"),
  );
  panel.append(button("使用随机密码", () => generateAndFill(field)));
  panel.append(button("暂不使用", hideGeneratorPrompt, true));
  document.documentElement.append(panel);
  positionMenu(panel, field);
}

function markPasswordFields(): void {
  document.querySelectorAll<HTMLInputElement>(passwordSelector).forEach((field) => {
    if (field.dataset.keyscanObserved) return;
    field.dataset.keyscanObserved = "true";
    field.setAttribute("data-keyscan", "password-field");
  });
}

function refreshLauncher(): void {
  if (!hasLauncherUi()) return;
  markPasswordFields();
  const target = getLauncherTarget();
  if (!target) {
    hideLauncher();
    return;
  }
  positionLauncher(target);
  maybeShowGeneratorPrompt(target);
}

function scheduleLauncherRefresh(): void {
  if (!hasLauncherUi()) return;
  if (launcherScheduled) return;
  launcherScheduled = true;
  browserWindow?.requestAnimationFrame(() => {
    launcherScheduled = false;
    refreshLauncher();
  });
}

function updatePageInfo(): void {
  const passwords = visiblePasswordFields();
  currentPageInfo = { origin: location.origin, passwordFields: passwords.length, scenario: detectScenario() };
  void warmNativeCache(currentPageInfo);
  scheduleLauncherRefresh();
}

function captureSubmittedCredential(form: HTMLFormElement): SubmittedCredential | null {
  if (!isSupportedOrigin(location.origin)) return null;
  const fields = visiblePasswordFields(form);
  if (!fields.length) return null;
  const scenario = detectScenario(form);
  if (scenario === "login" || scenario === "unknown") return null;

  const newPasswords = fields.filter((field) => isNewPasswordField(field, fields, scenario));
  const selectedPassword = (newPasswords[0] ?? fields[fields.length - 1])?.value ?? "";
  if (!selectedPassword) return null;

  const firstPassword = fields[0];
  let username = visibleTextFields(form)
    .filter((field) => Boolean(field.compareDocumentPosition(firstPassword) & Node.DOCUMENT_POSITION_FOLLOWING))
    .pop()?.value.trim() || "";

  const mode = scenario === "change_password" ? "update" : "save";
  const credentialSession = filledCredentialSession &&
    filledCredentialSession.origin === location.origin &&
    Date.now() - filledCredentialSession.createdAt <= sessionTtlMs
    ? filledCredentialSession
    : null;
  if (!username && mode === "update" && credentialSession?.username) username = credentialSession.username;
  if (!username) return null;
  const sessionPassword = validGeneratedPassword(username, mode === "update" ? "change_password" : "signup", selectedPassword);
  return {
    title: new URL(location.href).hostname || "KeyScan",
    username,
    password: sessionPassword ?? selectedPassword,
    mode,
    credentialId: mode === "update" ? credentialSession?.id : undefined,
  };
}

function showSavePrompt(credential: SubmittedCredential): void {
  savePrompt?.remove();
  const panel = document.createElement("div");
  savePrompt = panel;
  panel.style.position = "fixed";
  panel.style.right = "18px";
  panel.style.bottom = "18px";
  panel.style.zIndex = "2147483647";
  panel.style.width = "318px";
  panel.style.boxSizing = "border-box";
  panel.style.padding = "14px";
  panel.style.border = "1px solid rgba(96, 132, 176, 0.28)";
  panel.style.borderRadius = "18px";
  panel.style.background = "rgba(255,255,255,0.98)";
  panel.style.boxShadow = "0 16px 40px rgba(16, 33, 64, 0.22)";
  panel.style.color = "#17365d";
  panel.style.font = '13px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif';

  const title = heading(credential.mode === "update" ? "更新 KeyScan 密码？" : "保存到 KeyScan？");
  const sub = caption(`${credential.username} · ${credential.title}`);

  const label = document.createElement("label");
  label.textContent = "密码";
  label.style.display = "block";
  label.style.marginTop = "12px";
  label.style.fontWeight = "750";
  label.style.fontSize = "12px";

  const passwordInput = document.createElement("input");
  passwordInput.type = "password";
  passwordInput.value = credential.password;
  passwordInput.placeholder = "请输入要保存到 KeyScan 的密码";
  passwordInput.style.boxSizing = "border-box";
  passwordInput.style.width = "100%";
  passwordInput.style.marginTop = "6px";
  passwordInput.style.height = "38px";
  passwordInput.style.border = "1px solid rgba(96, 132, 176, 0.35)";
  passwordInput.style.borderRadius = "12px";
  passwordInput.style.padding = "0 10px";
  passwordInput.style.font = '13px system-ui,-apple-system,"Segoe UI",sans-serif';
  passwordInput.style.outline = "none";

  const reveal = document.createElement("button");
  reveal.type = "button";
  reveal.textContent = "显示/隐藏密码";
  reveal.style.marginTop = "7px";
  reveal.style.border = "0";
  reveal.style.background = "transparent";
  reveal.style.color = "#3979ed";
  reveal.style.cursor = "pointer";
  reveal.style.padding = "0";
  reveal.style.font = '12px system-ui,-apple-system,"Segoe UI",sans-serif';
  reveal.onclick = () => { passwordInput.type = passwordInput.type === "password" ? "text" : "password"; };

  const message = document.createElement("div");
  message.style.minHeight = "18px";
  message.style.marginTop = "7px";
  message.style.color = "#d35400";
  message.style.fontSize = "12px";

  const actions = document.createElement("div");
  actions.style.display = "flex";
  actions.style.gap = "8px";
  actions.style.marginTop = "12px";

  const save = document.createElement("button");
  save.type = "button";
  save.textContent = credential.mode === "update" ? "更新密码" : "保存";
  save.style.flex = "1";
  save.style.border = "0";
  save.style.borderRadius = "12px";
  save.style.padding = "10px";
  save.style.color = "#fff";
  save.style.fontWeight = "800";
  save.style.cursor = "pointer";
  save.style.background = "linear-gradient(135deg, #20b982 0%, #3979ed 100%)";

  const close = document.createElement("button");
  close.type = "button";
  close.textContent = "忽略";
  close.style.border = "0";
  close.style.borderRadius = "12px";
  close.style.padding = "10px 12px";
  close.style.cursor = "pointer";
  close.style.color = "#17365d";
  close.style.background = "#f3f7ff";
  close.onclick = () => {
    panel.remove();
    if (savePrompt === panel) savePrompt = null;
  };

  save.onclick = async () => {
    const password = passwordInput.value;
    if (password.length < 4) {
      passwordInput.focus();
      message.textContent = "请输入至少 4 位密码。";
      return;
    }
    save.disabled = true;
    save.textContent = "保存中…";
    message.textContent = "";
    const response = await sendRuntimeMessage<NativeResponse>({
      id: crypto.randomUUID(),
      type: "saveCredential",
      origin: location.origin,
      title: credential.title,
      username: credential.username,
      password,
      mode: credential.mode,
      credentialId: credential.credentialId,
    });
    if (response?.ok) {
      save.textContent = credential.mode === "update" ? "已更新" : "已保存";
      cacheState = "idle";
      globalThis.setTimeout(() => close.click(), 900);
      return;
    }
    save.disabled = false;
    save.textContent = response?.error === "LOCKED" ? "请先解锁 KeyScan" : "保存失败";
    message.textContent = response?.error === "LOCKED" ? "桌面端已锁定，解锁后再保存。" : "未能保存，请确认桌面端和插件已连接。";
  };

  actions.append(save, close);
  panel.append(title, sub, label, passwordInput, reveal, message, actions);
  document.documentElement.append(panel);
}

markPasswordFields();
updatePageInfo();

if (typeof MutationObserver !== "undefined") {
  new MutationObserver(updatePageInfo).observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["class", "style", "hidden", "autocomplete", "type"],
  });
}

if (typeof browserWindow?.addEventListener === "function") {
  browserWindow.addEventListener("scroll", scheduleLauncherRefresh, true);
  browserWindow.addEventListener("resize", scheduleLauncherRefresh, true);
  browserWindow.addEventListener("focusin", scheduleLauncherRefresh, true);
}

document.addEventListener("pointerdown", (event) => {
  const target = event.target as Node | null;
  if (!target) return;
  if (launcherMenu?.contains(target) || launcherHost?.contains(target) || generatorPrompt?.contains(target)) return;
  hideMenu();
  hideGeneratorPrompt();
}, true);

document.addEventListener("focusin", (event) => {
  const target = event.target;
  if (target instanceof HTMLInputElement && target.matches(passwordSelector) && isVisibleField(target)) {
    launcherTarget = target;
    scheduleLauncherRefresh();
    maybeShowGeneratorPrompt(target, true);
  }
}, true);

document.addEventListener("submit", (event) => {
  const form = event.target;
  if (!(form instanceof HTMLFormElement)) return;
  const capture = captureSubmittedCredential(form);
  if (capture) showSavePrompt(capture);
}, true);

document.addEventListener("click", (event) => {
  const target = event.target;
  if (!(target instanceof HTMLElement)) return;
  const submitter = target.closest('button[type="submit"],input[type="submit"],button:not([type])');
  if (!(submitter instanceof HTMLElement)) return;
  const form = submitter.closest("form");
  if (!(form instanceof HTMLFormElement)) return;
  globalThis.setTimeout(() => {
    const capture = captureSubmittedCredential(form);
    if (capture) showSavePrompt(capture);
  }, 160);
}, true);

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === "pageInfo") {
    updatePageInfo();
    sendResponse(currentPageInfo);
    return;
  }
  if (message?.type === "fillCredential") {
    if (typeof message.origin !== "string" || message.origin !== location.origin || !isSupportedOrigin(location.origin)) {
      sendResponse({ filled: false, error: "ORIGIN_CHANGED" });
      return;
    }
    const credential = message.credential as { username?: unknown; password?: unknown } | undefined;
    if (typeof credential?.username !== "string" || typeof credential.password !== "string") {
      sendResponse({ filled: false });
      return;
    }
    sendResponse({ filled: fillCredentialLocally({ username: credential.username, password: credential.password }) });
  }
});
