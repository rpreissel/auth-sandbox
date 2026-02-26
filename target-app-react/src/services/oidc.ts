// ── PKCE helpers ─────────────────────────────────────────────────────────────

const KEYCLOAK_AUTH_ENDPOINT =
  'https://keycloak.localhost:8443/realms/auth-sandbox/protocol/openid-connect/auth';
const CLIENT_ID      = 'target-app-client';
const REDIRECT_URI   = 'https://target-app.localhost:8443/callback';
const SCOPE          = 'openid profile';

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
  const json = await resp.json().catch(() => ({})) as Record<string, unknown>;
  if (!resp.ok) {
    const msg = (json['error_description'] ?? json['error'] ?? resp.statusText) as string;
    throw new Error(`Userinfo failed: ${msg}`);
  }
  return json;
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
): Promise<import('../types').OidcTokens> {
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
    const err = await resp.json().catch(() => ({})) as Record<string, unknown>;
    const msg = (err['error_description'] ?? err['error'] ?? resp.statusText) as string;
    throw new Error(`Token exchange failed: ${msg}`);
  }

  const json = await resp.json() as Record<string, unknown>;
  return {
    access_token:  json['access_token'] as string,
    id_token:      json['id_token'] as string,
    refresh_token: json['refresh_token'] as string,
  };
}
