import type { OidcTokens } from '../types';

const KEYCLOAK_AUTH_ENDPOINT = import.meta.env.VITE_KEYCLOAK_AUTH_ENDPOINT || 'https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth';
const KEYCLOAK_LOGOUT_ENDPOINT = import.meta.env.VITE_KEYCLOAK_LOGOUT_ENDPOINT || 'https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/logout';
const CLIENT_ID      = import.meta.env.VITE_CLIENT_ID || 'cms-client';
const REDIRECT_URI   = import.meta.env.VITE_REDIRECT_URI || 'https://cms.localhost:8443/cms/callback';
const SCOPE          = import.meta.env.VITE_SCOPE || 'openid profile';

const TOKEN_PROXY_PATH = '/api/token';

const CODE_VERIFIER_KEY  = 'pkce_code_verifier';
const AUTH_STATE_KEY     = 'pkce_state';
const TOKENS_KEY         = 'oidc_tokens';

function randomBase64Url(byteLength: number): string {
  const buf = crypto.getRandomValues(new Uint8Array(byteLength));
  return btoa(String.fromCharCode(...buf))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

async function sha256Base64Url(plain: string): Promise<string> {
  const encoder = new TextEncoder();
  const data    = encoder.encode(plain);
  const digest  = await crypto.subtle.digest('SHA-256', data);
  return btoa(String.fromCharCode(...new Uint8Array(digest)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

export function generateCodeVerifier(): string {
  return randomBase64Url(32);
}

export async function generateCodeChallenge(verifier: string): Promise<string> {
  return sha256Base64Url(verifier);
}

export async function buildAuthUrl(): Promise<string> {
  const verifier   = generateCodeVerifier();
  const challenge  = await generateCodeChallenge(verifier);
  const state      = randomBase64Url(16);

  sessionStorage.setItem(CODE_VERIFIER_KEY, verifier);
  sessionStorage.setItem(AUTH_STATE_KEY, state);

  const params = new URLSearchParams({
    response_type:          'code',
    client_id:              CLIENT_ID,
    redirect_uri:           REDIRECT_URI,
    scope:                  SCOPE,
    state:                  state,
    code_challenge:         challenge,
    code_challenge_method:  'S256',
  });

  return `${KEYCLOAK_AUTH_ENDPOINT}?${params.toString()}`;
}

export async function exchangeCode(
  code: string,
  returnedState: string,
): Promise<OidcTokens> {
  const savedState    = sessionStorage.getItem(AUTH_STATE_KEY);
  const codeVerifier  = sessionStorage.getItem(CODE_VERIFIER_KEY);

  if (savedState !== returnedState) {
    throw new Error('OAuth state mismatch — possible CSRF');
  }
  if (!codeVerifier) {
    throw new Error('Missing PKCE code verifier in session storage');
  }

  sessionStorage.removeItem(CODE_VERIFIER_KEY);
  sessionStorage.removeItem(AUTH_STATE_KEY);

  const body = new URLSearchParams({
    grant_type:    'authorization_code',
    client_id:     CLIENT_ID,
    redirect_uri:  REDIRECT_URI,
    code:          code,
    code_verifier: codeVerifier,
  });

  const resp = await fetch(TOKEN_PROXY_PATH, {
    method:  'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body:    body.toString(),
  });

  if (!resp.ok) {
    throw new Error(`Token exchange failed: ${resp.status} ${await resp.text()}`);
  }

  const json = await resp.json() as Record<string, unknown>;
  const tokens: OidcTokens = {
    access_token:  json['access_token'] as string,
    id_token:      json['id_token'] as string,
    refresh_token: json['refresh_token'] as string,
  };
  saveTokens(tokens);
  return tokens;
}

export async function refreshTokens(refreshToken: string): Promise<OidcTokens> {
  const body = new URLSearchParams({
    grant_type:    'refresh_token',
    client_id:     CLIENT_ID,
    refresh_token: refreshToken,
  });

  const resp = await fetch(TOKEN_PROXY_PATH, {
    method:  'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body:    body.toString(),
  });

  if (!resp.ok) {
    throw new Error(`Token refresh failed: ${resp.status}`);
  }

  const json = await resp.json() as Record<string, unknown>;
  const tokens: OidcTokens = {
    access_token:  json['access_token'] as string,
    id_token:      json['id_token'] as string,
    refresh_token: json['refresh_token'] as string,
  };
  saveTokens(tokens);
  return tokens;
}

export async function logout(refreshToken: string, idToken?: string): Promise<void> {
  await Promise.allSettled([
    revokeToken(refreshToken, 'refresh_token'),
  ]);

  clearTokens();

  const logoutUrl = new URL(KEYCLOAK_LOGOUT_ENDPOINT);
  logoutUrl.searchParams.set('post_logout_redirect_uri', window.location.origin + '/');
  if (idToken) {
    logoutUrl.searchParams.set('id_token_hint', idToken);
  }
  window.location.href = logoutUrl.toString();
}

async function revokeToken(token: string, tokenTypeHint: string): Promise<void> {
  const body = new URLSearchParams({
    client_id:      CLIENT_ID,
    token:          token,
    token_type_hint: tokenTypeHint,
  });

  try {
    await fetch(TOKEN_PROXY_PATH.replace('token', 'revoke'), {
      method:  'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body:    body.toString(),
    });
  } catch {
    // Best effort - continue with logout even if revocation fails
  }
}

export function saveTokens(tokens: OidcTokens): void {
  localStorage.setItem(TOKENS_KEY, JSON.stringify(tokens));
}

export function getTokens(): OidcTokens | null {
  const stored = localStorage.getItem(TOKENS_KEY);
  if (!stored) return null;
  try {
    return JSON.parse(stored) as OidcTokens;
  } catch {
    return null;
  }
}

export function clearTokens(): void {
  localStorage.removeItem(TOKENS_KEY);
  sessionStorage.removeItem(CODE_VERIFIER_KEY);
  sessionStorage.removeItem(AUTH_STATE_KEY);
}

export function getAccessToken(): string | null {
  const tokens = getTokens();
  return tokens?.access_token ?? null;
}
