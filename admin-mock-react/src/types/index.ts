export interface RegistrationCode {
  id: string;
  userId: string;
  name: string;
  activationCode: string;
  expiresAt: string | null;
  useCount: number;
  createdAt: string | null;
}

export interface Device {
  id: string;
  userId: string;
  name: string;
  deviceId: string;
  keycloakUserId: string | null;
  createdAt: string | null;
}

export type LogLevel = 'ok' | 'err' | 'warn' | 'info' | '';

export interface LogEntry {
  id: number;
  ts: string;
  msg: string;
  level: LogLevel;
}

export type StatusKind = 'idle' | 'success' | 'error' | 'pending';

export interface SyncResult {
  synced: number;
  alreadySynced: number;
  failed: number;
}

export interface CleanupResult {
  deleted: number;
  skipped: number;
}

export interface OidcTokens {
  access_token: string;
  id_token: string;
  refresh_token: string;
}
