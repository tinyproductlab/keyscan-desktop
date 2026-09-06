export const NATIVE_HOST = "com.keyscan.desktop";

export type NativeRequest =
  | { type: "openPopup" }
  | { id: string; type: "register"; browser: "chrome" | "edge" | "brave" | "firefox" | "safari"; extensionId: string; version: string; token?: string }
  | { id: string; type: "heartbeat"; token: string }
  | { id: string; type: "status"; token?: string }
  | { id: string; type: "unlock"; token?: string }
  | { id: string; type: "findCredentials"; origin: string; token?: string }
  | { id: string; type: "requestCredential"; origin: string; credentialId: string; token?: string }
  | { id: string; type: "generatePassword"; origin: string; length?: number; mode?: "recommended" | "compatible" | "pin"; token?: string }
  | { id: string; type: "saveCredential"; origin: string; title: string; username: string; password: string; mode?: "save" | "update"; credentialId?: string; token?: string };

export type NativeResponse = {
  id: string;
  ok: boolean;
  state?: "locked" | "unlocked" | "paired";
  error?: "HOST_NOT_FOUND" | "LOCKED" | "DENIED" | "PAIRING_REQUIRED" | "PAIRING_DENIED" | "INVALID_REQUEST" | "INTERNAL" | "RESPONSE_TOO_LARGE";
  diagnostic?: string;
  credentials?: Array<{ id: string; label: string; username: string }>;
  credential?: { username: string; password: string };
  generatedPassword?: string;
  pairingToken?: string;
};
