// ── PKCE helpers ─────────────────────────────────────────────────────────────
import { handleApiResponse } from '@auth-sandbox/utils';
import type { OidcTokens } from '../types';

const KEYCLOAK_AUTH_ENDPOINT = import.meta.env.VITE_KEYCLOAK_AUTH_ENDPOINT || 'https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth';
const KEYCLOAK_LOGOUT_ENDPOINT = import.meta.env.VITE_KEYCLOAK_LOGOUT_ENDPOINT || 'https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/logout';
const CLIENT_ID      = import.meta.env.VITE_CLIENT_ID || 'target-app-client';
const REDIRECT_URI   = import.meta.env.VITE_REDIRECT_URI || 'https://target-app.localhost:8443/callback';
const SCOPE          = import.meta.env.VITE_SCOPE || 'openid profile';

// Token exchange goes through Caddy proxy (/api/token → Keycloak token endpoint)
// to avoid CORS issues when calling Keycloak directly from the browser.
const TOKEN_PROXY_PATH = '/api/token';

const CODE_VERIFIER_KEY  = 'pkce_code_verifier';
const AUTH_STATE_KEY     = 'pkce_state';

// ── Crypto ────────────────────────────────────────────────────────────────────

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

/**
 * Calls the Keycloak userinfo endpoint via the /api/userinfo Nginx proxy.
 */
export async function fetchUserinfo(accessToken: string): Promise<Record<string, unknown>> {
  const resp = await fetch('/api/userinfo', {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  return handleApiResponse<Record<string, unknown>>(resp);
}

export function generateCodeVerifier(): string {
  return randomBase64Url(32);
}

export async function generateCodeChallenge(verifier: string): Promise<string> {
  return sha256Base64Url(verifier);
}

// ── OIDC flow ─────────────────────────────────────────────────────────────────

/**
 * Builds the Keycloak authorization URL, persists the code_verifier and state
 * in sessionStorage, and returns the URL to redirect to.
 */
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

/**
 * Exchanges the authorization code for tokens via the Caddy /api/token proxy.
 * Validates the state parameter and clears sessionStorage afterwards.
 */
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

  const json = await handleApiResponse<Record<string, unknown>>(resp);
  return {
    access_token:  json['access_token'] as string,
    id_token:      json['id_token'] as string,
    refresh_token: json['refresh_token'] as string,
  };
}

/**
 * Refreshes the access token using the refresh_token grant.
 */
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

  const json = await handleApiResponse<Record<string, unknown>>(resp);
  return {
    access_token:  json['access_token'] as string,
    id_token:      json['id_token'] as string,
    refresh_token: json['refresh_token'] as string,
  };
}

/**
 * Logs out the user by revoking tokens and redirecting to Keycloak logout endpoint.
 */
export async function logout(refreshToken: string): Promise<void> {
  await Promise.allSettled([
    revokeToken(refreshToken, 'refresh_token'),
  ]);

  const logoutUrl = new URL(KEYCLOAK_LOGOUT_ENDPOINT);
  logoutUrl.searchParams.set('post_logout_redirect_uri', REDIRECT_URI);
  window.location.href = logoutUrl.toString();
}

/**
 * Revokes a token at the Keycloak token revocation endpoint.
 */
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
