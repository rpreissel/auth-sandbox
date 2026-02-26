import type { Challenge, OidcTokens } from '../types';

let baseUrl = '';

export function setBaseUrl(url: string): void {
  baseUrl = url.replace(/\/$/, '');
}

export function getBaseUrl(): string {
  return baseUrl;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const url  = baseUrl + path;
  const resp = await fetch(url, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(body),
  });
  const json = await resp.json().catch(() => ({})) as Record<string, unknown>;
  if (!resp.ok) {
    const msg = (json['message'] ?? json['error'] ?? resp.statusText) as string;
    throw new Error(`HTTP ${resp.status}: ${msg}`);
  }
  return json as T;
}

export interface RegisterResponse {
  message: string;
  deviceId: string;
}

export function registerDevice(payload: {
  deviceId: string;
  userId: string;
  name: string;
  activationCode: string;
  publicKey: string;
}): Promise<RegisterResponse> {
  return post('/api/v1/devices/register', payload);
}

export function startLogin(deviceId: string): Promise<Challenge> {
  return post('/api/v1/auth/login/start', { deviceId });
}

export function verifyLogin(nonce: string, signature: string): Promise<OidcTokens> {
  return post('/api/v1/auth/login/verify', { nonce, signature });
}

export function refreshTokens(refreshToken: string): Promise<OidcTokens> {
  return post('/api/v1/auth/token/refresh', { refreshToken });
}

export async function fetchUserinfo(accessToken: string): Promise<Record<string, unknown>> {
  const resp = await fetch('/api/userinfo', {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  const json = await resp.json().catch(() => ({})) as Record<string, unknown>;
  if (!resp.ok) {
    const msg = (json['message'] ?? json['error'] ?? resp.statusText) as string;
    throw new Error(`HTTP ${resp.status}: ${msg}`);
  }
  return json;
}

export interface InitiateTransferResponse {
  transferUrl: string;
  expiresInSeconds: number;
}

const AUTH_SERVICE_URL = 'https://auth-service.localhost:8443';
const TARGET_APP_URL = 'https://target-app.localhost:8443';

/**
 * Initiates a browser SSO transfer session via auth-service.
 * The app passes its current Keycloak access token; auth-service introspects it,
 * runs PAR against Keycloak, and returns a one-time transfer URL (valid 60 s).
 * The user opens that URL in a separate browser window — auth-service deposits a
 * Keycloak SSO session cookie, then redirects to target-app which auto-starts
 * a standard OIDC Auth Code + PKCE flow.
 */
export async function initiateTransfer(accessToken: string): Promise<InitiateTransferResponse> {
  const resp = await fetch(`${AUTH_SERVICE_URL}/api/v1/transfer/init`, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify({ accessToken, targetUrl: TARGET_APP_URL }),
  });
  const json = await resp.json().catch(() => ({})) as Record<string, unknown>;
  if (!resp.ok) {
    const msg = (json['message'] ?? json['error'] ?? resp.statusText) as string;
    throw new Error(`HTTP ${resp.status}: ${msg}`);
  }
  return json as unknown as InitiateTransferResponse;
}
