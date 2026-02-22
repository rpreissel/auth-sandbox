// ── Domain types ──────────────────────────────────────────────────

export type Screen = 'unregistered' | 'home' | 'authenticated';

export interface DeviceBinding {
  deviceId: string;
  publicPem: string;
  userId: string;
  name: string;
}

export interface Challenge {
  nonce: string;
  challenge: string;
  expiresInSeconds: number;
}

export interface OidcTokens {
  access_token: string;
  id_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
  scope: string;
}

export interface LogEntry {
  id: number;
  ts: string;
  msg: string;
  level: 'info' | 'ok' | 'err' | 'warn';
}

export interface JwtParsed {
  header: Record<string, unknown>;
  payload: Record<string, unknown>;
  signature: string;
  raw: { header: string; payload: string; signature: string };
}
