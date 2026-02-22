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
