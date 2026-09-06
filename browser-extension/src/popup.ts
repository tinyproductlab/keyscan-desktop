import type { NativeResponse } from "./protocol";

declare const __KEYSCAN_BROWSER__: "chrome" | "edge" | "brave" | "firefox" | "safari";

const strings = {
  "zh-CN": { tagline: "安全 免费", status: "正在连接 KeyScan…", unavailable: "未连接到 KeyScan 桌面版", hint: "请先安装并打开 KeyScan 桌面版。", connect: "连接 KeyScan 桌面版", pairing: "请在 KeyScan 桌面版点击允许。", locked: "保险箱已锁定", unlock: "请在 KeyScan 中使用 PIN 或设备验证解锁。", ready: "KeyScan 已解锁", noLogin: "此页面未发现密码输入框", found: (n: number) => `发现 ${n} 个密码输入框`, confirm: "请在 KeyScan 桌面版确认这次填充。", denied: "填充请求已拒绝或已过期。", filled: "凭据已填入，请检查页面后自行提交。", noEditable: "页面已变化或没有可编辑的登录栏。" },
  "zh-TW": { tagline: "安全 免費", status: "正在連線 KeyScan…", unavailable: "未連線到 KeyScan 桌面版", hint: "請先安裝並開啟 KeyScan 桌面版。", connect: "連線 KeyScan 桌面版", pairing: "請在 KeyScan 桌面版點擊允許。", locked: "保險箱已鎖定", unlock: "請在 KeyScan 中使用 PIN 或裝置驗證解鎖。", ready: "KeyScan 已解鎖", noLogin: "此頁面未發現密碼輸入欄位", found: (n: number) => `發現 ${n} 個密碼輸入欄位`, confirm: "請在 KeyScan 桌面版確認這次填入。", denied: "填入要求已拒絕或已逾時。", filled: "憑證已填入，請檢查頁面後自行送出。", noEditable: "頁面已變更或沒有可編輯的登入欄位。" },
  ja: { tagline: "安全 無料", status: "KeyScan に接続しています…", unavailable: "KeyScan デスクトップに接続できません", hint: "KeyScan デスクトップを起動してください。", connect: "KeyScan デスクトップに接続", pairing: "KeyScan デスクトップで「許可」をクリックしてください。", locked: "保管庫はロックされています", unlock: "KeyScan で PIN または端末認証を使って解除してください。", ready: "KeyScan はロック解除済みです", noLogin: "このページにパスワード欄はありません", found: (n: number) => `${n} 個のパスワード欄を検出しました`, confirm: "KeyScan デスクトップで今回の入力を確認してください。", denied: "入力要求は拒否されたか期限切れです。", filled: "認証情報を入力しました。確認してからご自身で送信してください。", noEditable: "ページが変わったか、編集可能なログイン欄がありません。" },
  ko: { tagline: "안전 무료", status: "KeyScan에 연결 중…", unavailable: "KeyScan 데스크톱에 연결되지 않았습니다", hint: "KeyScan 데스크톱을 설치하고 실행하세요.", connect: "KeyScan 데스크톱 연결", pairing: "KeyScan 데스크톱에서 [허용]을 클릭하세요.", locked: "보관함이 잠겨 있습니다", unlock: "KeyScan에서 PIN 또는 기기 인증으로 잠금을 해제하세요.", ready: "KeyScan 잠금이 해제되었습니다", noLogin: "이 페이지에서 비밀번호 입력란을 찾지 못했습니다", found: (n: number) => `비밀번호 입력란 ${n}개를 찾았습니다`, confirm: "KeyScan 데스크톱에서 이번 입력을 확인하세요.", denied: "입력 요청이 거부되었거나 만료되었습니다.", filled: "자격 증명을 입력했습니다. 페이지를 확인한 뒤 직접 제출하세요.", noEditable: "페이지가 변경되었거나 편집 가능한 로그인 필드가 없습니다." },
  de: { tagline: "safe free", status: "Verbindung mit KeyScan…", unavailable: "KeyScan Desktop ist nicht verbunden", hint: "Installieren und öffnen Sie KeyScan Desktop.", connect: "KeyScan Desktop verbinden", pairing: "Klicken Sie in KeyScan Desktop auf „Zulassen“.", locked: "Tresor ist gesperrt", unlock: "Entsperren Sie KeyScan mit PIN oder Geräteauthentifizierung.", ready: "KeyScan ist entsperrt", noLogin: "Kein Passwortfeld auf dieser Seite", found: (n: number) => `${n} Passwortfeld${n === 1 ? "" : "er"} gefunden`, confirm: "Bestätigen Sie dieses einmalige Ausfüllen in KeyScan Desktop.", denied: "Die Anfrage wurde abgelehnt oder ist abgelaufen.", filled: "Anmeldedaten eingefügt. Prüfen Sie die Seite und senden Sie selbst ab.", noEditable: "Die Seite wurde geändert oder enthält keine bearbeitbaren Anmeldefelder." },
  es: { tagline: "safe free", status: "Conectando con KeyScan…", unavailable: "KeyScan Desktop no está conectado", hint: "Instala y abre KeyScan Desktop.", connect: "Conectar KeyScan Desktop", pairing: "Haz clic en Permitir en la aplicación KeyScan Desktop.", locked: "La bóveda está bloqueada", unlock: "Desbloquea KeyScan con el PIN o la autenticación del dispositivo.", ready: "KeyScan está desbloqueado", noLogin: "No hay campos de contraseña en esta página", found: (n: number) => `${n} campo${n === 1 ? "" : "s"} de contraseña`, confirm: "Confirma este rellenado único en KeyScan Desktop.", denied: "La solicitud fue rechazada o caducó.", filled: "Credenciales rellenadas. Revisa la página y envíala tú mismo.", noEditable: "La página cambió o no hay campos de acceso editables." },
  fr: { tagline: "safe free", status: "Connexion à KeyScan…", unavailable: "KeyScan Desktop n’est pas connecté", hint: "Installez et ouvrez KeyScan Desktop.", connect: "Connecter KeyScan Desktop", pairing: "Cliquez sur Autoriser dans l’application KeyScan Desktop.", locked: "Le coffre est verrouillé", unlock: "Déverrouillez KeyScan avec le PIN ou l’authentification de l’appareil.", ready: "KeyScan est déverrouillé", noLogin: "Aucun champ de mot de passe sur cette page", found: (n: number) => `${n} champ${n === 1 ? "" : "s"} de mot de passe`, confirm: "Confirmez ce remplissage unique dans KeyScan Desktop.", denied: "La demande a été refusée ou a expiré.", filled: "Identifiants remplis. Vérifiez la page et envoyez-la vous-même.", noEditable: "La page a changé ou aucun champ de connexion n’est modifiable." },
  it: { tagline: "safe free", status: "Connessione a KeyScan…", unavailable: "KeyScan Desktop non è connesso", hint: "Installa e apri KeyScan Desktop.", connect: "Connetti KeyScan Desktop", pairing: "Fai clic su Consenti nell’app KeyScan Desktop.", locked: "La cassaforte è bloccata", unlock: "Sblocca KeyScan con il PIN o l’autenticazione del dispositivo.", ready: "KeyScan è sbloccato", noLogin: "Nessun campo password in questa pagina", found: (n: number) => `${n} camp${n === 1 ? "o" : "i"} password`, confirm: "Conferma questo riempimento singolo in KeyScan Desktop.", denied: "La richiesta è stata rifiutata o è scaduta.", filled: "Credenziali inserite. Controlla la pagina e inviala personalmente.", noEditable: "La pagina è cambiata o non contiene campi di accesso modificabili." },
  nl: { tagline: "safe free", status: "Verbinden met KeyScan…", unavailable: "KeyScan Desktop is niet verbonden", hint: "Installeer en open KeyScan Desktop.", connect: "KeyScan Desktop verbinden", pairing: "Klik op Toestaan in de KeyScan Desktop-app.", locked: "Kluis is vergrendeld", unlock: "Ontgrendel KeyScan met uw pincode of apparaatauthenticatie.", ready: "KeyScan is ontgrendeld", noLogin: "Geen wachtwoordveld op deze pagina", found: (n: number) => `${n} wachtwoordveld${n === 1 ? "" : "en"} gevonden`, confirm: "Bevestig deze eenmalige invulling in KeyScan Desktop.", denied: "De aanvraag is geweigerd of verlopen.", filled: "Inloggegevens ingevuld. Controleer de pagina en verzend zelf.", noEditable: "De pagina is gewijzigd of heeft geen bewerkbare inlogvelden." },
  pt: { tagline: "safe free", status: "Conectando ao KeyScan…", unavailable: "O KeyScan Desktop não está conectado", hint: "Instale e abra o KeyScan Desktop.", connect: "Ligar o KeyScan Desktop", pairing: "Clique em Permitir na aplicação KeyScan Desktop.", locked: "O cofre está bloqueado", unlock: "Desbloqueie o KeyScan com o PIN ou a autenticação do dispositivo.", ready: "O KeyScan está desbloqueado", noLogin: "Nenhum campo de senha nesta página", found: (n: number) => `${n} campo${n === 1 ? "" : "s"} de senha`, confirm: "Confirme este preenchimento único no KeyScan Desktop.", denied: "A solicitação foi negada ou expirou.", filled: "Credenciais preenchidas. Confira a página e envie você mesmo.", noEditable: "A página mudou ou não há campos de login editáveis." },
  ru: { tagline: "safe free", status: "Подключение к KeyScan…", unavailable: "KeyScan Desktop не подключён", hint: "Установите и откройте KeyScan Desktop.", connect: "Подключить KeyScan Desktop", pairing: "Нажмите «Разрешить» в приложении KeyScan Desktop.", locked: "Хранилище заблокировано", unlock: "Разблокируйте KeyScan с помощью PIN-кода или проверки устройства.", ready: "KeyScan разблокирован", noLogin: "На странице нет поля пароля", found: (n: number) => `Найдено полей пароля: ${n}`, confirm: "Подтвердите это разовое заполнение в KeyScan Desktop.", denied: "Запрос отклонён или истёк.", filled: "Данные заполнены. Проверьте страницу и отправьте форму самостоятельно.", noEditable: "Страница изменилась или доступных полей входа нет." },
  en: { tagline: "safe free", status: "Connecting to KeyScan…", unavailable: "KeyScan desktop is not connected", hint: "Install and open the KeyScan desktop app.", connect: "Connect KeyScan desktop", pairing: "Click Allow in the KeyScan desktop app.", locked: "Vault is locked", unlock: "Unlock KeyScan with your PIN or device authentication.", ready: "KeyScan is unlocked", noLogin: "No password field found on this page", found: (n: number) => `${n} password field${n === 1 ? "" : "s"} found`, confirm: "Confirm this one-time fill in the KeyScan desktop app.", denied: "Fill request was denied or expired.", filled: "Credential filled. Review the page and submit it yourself.", noEditable: "The page changed or no editable login fields were found." }
};

const locale = navigator.language;
const t = strings[locale as keyof typeof strings] ?? strings[locale.split("-")[0] as keyof typeof strings] ?? strings.en;
const connectText = "connect" in t ? t.connect : strings.en.connect;
const pairingText = "pairing" in t ? t.pairing : strings.en.pairing;
const generatedPasswordText: Record<string, { action: string; filled: string }> = {
  "zh-CN": { action: "生成高强度密码", filled: "已填入高强度密码。提交前请先将它保存到 KeyScan。" },
  "zh-TW": { action: "產生高強度密碼", filled: "已填入高強度密碼。送出前請先將它儲存到 KeyScan。" },
  ja: { action: "強力なパスワードを生成", filled: "強力なパスワードを入力しました。送信する前に KeyScan に保存してください。" },
  ko: { action: "강력한 비밀번호 생성", filled: "강력한 비밀번호를 입력했습니다. 제출하기 전에 KeyScan에 저장하세요." },
  de: { action: "Starkes Passwort erzeugen", filled: "Starkes Passwort eingefügt. Speichern Sie es vor dem Absenden in KeyScan." },
  es: { action: "Generar contraseña segura", filled: "Se rellenó una contraseña segura. Guárdala en KeyScan antes de enviarla." },
  fr: { action: "Générer un mot de passe fort", filled: "Mot de passe fort rempli. Enregistrez-le dans KeyScan avant l’envoi." },
  it: { action: "Genera password sicura", filled: "Password sicura inserita. Salvala in KeyScan prima di inviare." },
  nl: { action: "Sterk wachtwoord genereren", filled: "Sterk wachtwoord ingevuld. Sla het op in KeyScan voordat u verzendt." },
  pt: { action: "Gerar palavra-passe forte", filled: "Palavra-passe forte preenchida. Guarde-a no KeyScan antes de enviar." },
  ru: { action: "Создать надежный пароль", filled: "Надежный пароль заполнен. Сохраните его в KeyScan перед отправкой." },
  en: { action: "Generate strong password", filled: "Strong password filled. Save it in KeyScan before submitting." }
};
const generated = generatedPasswordText[locale] ?? generatedPasswordText[locale.split("-")[0]] ?? generatedPasswordText.en;
const title = document.querySelector<HTMLElement>("#status")!;
const detail = document.querySelector<HTMLElement>("#detail")!;
const credentialList = document.querySelector<HTMLElement>("#credentials")!;
document.querySelector<HTMLElement>("#tagline")!.textContent = t.tagline;
title.textContent = t.status;

async function sendRuntimeMessage<T>(message: unknown): Promise<T | undefined> {
  try {
    return await chrome.runtime.sendMessage(message) as T;
  } catch {
    return undefined;
  }
}

async function sendTabMessage<T>(tabId: number, message: unknown): Promise<T | undefined> {
  try {
    return await chrome.tabs.sendMessage(tabId, message) as T;
  } catch {
    return undefined;
  }
}

async function start(): Promise<void> {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  let pageInfo: { passwordFields: number; signUp?: boolean; origin?: string } = { passwordFields: 0 };
  if (tab.id) {
    const response = await sendTabMessage<typeof pageInfo>(tab.id, { type: "pageInfo" });
    if (response) pageInfo = response;
  }
  const response = await sendRuntimeMessage<NativeResponse>({ id: crypto.randomUUID(), type: "status" });
  if (!response?.ok) {
    if (response?.error === "PAIRING_REQUIRED" || response?.error === "PAIRING_DENIED" || response?.error === "HOST_NOT_FOUND" || !response) {
      showConnectButton(response);
      return;
    }
    title.textContent = t.unavailable;
    detail.textContent = response?.diagnostic ? `${response.error}: ${response.diagnostic}` : `${t.hint} (${response?.error ?? "NO_RESPONSE"})`;
    return;
  }
  if (response.state === "locked") { title.textContent = t.locked; detail.textContent = t.unlock; return; }
  title.textContent = t.ready;
  detail.textContent = pageInfo.passwordFields ? t.found(pageInfo.passwordFields) : t.noLogin;
  credentialList.replaceChildren();
  if (tab.id && pageInfo.signUp && pageInfo.origin && String(pageInfo.origin).startsWith("https://")) {
    const generate = document.createElement("button");
    generate.type = "button"; generate.className = "credential"; generate.textContent = generated.action;
    generate.addEventListener("click", async () => {
      const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*-_=+";
      const bytes = crypto.getRandomValues(new Uint32Array(20));
      const password = Array.from(bytes, value => alphabet[value % alphabet.length]).join("");
      const result = await sendTabMessage<{ filled?: boolean }>(tab.id!, { type: "fillGeneratedPassword", origin: pageInfo.origin, password });
      detail.textContent = result?.filled ? generated.filled : t.noEditable;
      window.setTimeout(() => window.close(), 1500);
    });
    credentialList.append(generate);
  }
  if (!tab.id || !pageInfo.passwordFields || !("origin" in pageInfo) || !String(pageInfo.origin).startsWith("https://")) return;
  const found = await sendRuntimeMessage<NativeResponse>({ id: crypto.randomUUID(), type: "findCredentials", origin: pageInfo.origin });
  if (!found?.ok || !found.credentials?.length) return;
  for (const credential of found.credentials) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "credential";
    button.textContent = `${credential.label}  ·  ${credential.username}`;
    button.addEventListener("click", async () => {
      button.disabled = true;
      detail.textContent = t.confirm;
      const released = await sendRuntimeMessage<NativeResponse>({
        id: crypto.randomUUID(), type: "requestCredential", origin: pageInfo.origin, credentialId: credential.id
      });
      if (!released?.ok || !released.credential) {
        detail.textContent = released?.error === "DENIED" ? t.denied : t.unavailable;
        button.disabled = false;
        return;
      }
      const fill = released.credential;
      const result = await sendTabMessage<{ filled?: boolean }>(tab.id!, { type: "fillCredential", origin: pageInfo.origin, credential: fill });
      released.credential = undefined;
      detail.textContent = result?.filled ? t.filled : t.noEditable;
      window.setTimeout(() => window.close(), 1200);
    });
    credentialList.append(button);
  }
}

function showConnectButton(response?: NativeResponse): void {
  title.textContent = t.unavailable;
  detail.textContent = response?.diagnostic ? `${response.error}: ${response.diagnostic}` : `${t.hint} (${response?.error ?? "NO_RESPONSE"})`;
  credentialList.replaceChildren();
  const button = document.createElement("button");
  button.type = "button";
  button.className = "credential";
  button.textContent = connectText;
  button.addEventListener("click", async () => {
    button.disabled = true;
    detail.textContent = pairingText;
    const paired = await sendRuntimeMessage<NativeResponse>({
      id: crypto.randomUUID(),
      type: "register",
      browser: __KEYSCAN_BROWSER__,
      extensionId: chrome.runtime.id,
      version: chrome.runtime.getManifest().version,
    });
    if (paired?.ok) {
      await start();
      return;
    }
    button.disabled = false;
    detail.textContent = paired?.diagnostic ? `${paired.error}: ${paired.diagnostic}` : `${t.hint} (${paired?.error ?? "NO_RESPONSE"})`;
  });
  credentialList.append(button);
}

void start();
