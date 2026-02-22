import type { JwtParsed } from '../types';

// ── Random device ID ──────────────────────────────────────────────

export function generateDeviceId(): string {
  const arr = crypto.getRandomValues(new Uint8Array(8));
  return 'device-' + Array.from(arr).map(b => b.toString(16).padStart(2, '0')).join('');
}

// ── Key generation ────────────────────────────────────────────────

export async function generateKeyPair(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey(
    {
      name: 'RSASSA-PKCS1-v1_5',
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: 'SHA-256',
    },
    false, // private key not extractable — simulates Secure Enclave
    ['sign', 'verify'],
  );
}

export async function exportPublicKeyPem(key: CryptoKey): Promise<string> {
  const spki  = await crypto.subtle.exportKey('spki', key);
  const b64   = btoa(String.fromCharCode(...new Uint8Array(spki)));
  const lines = b64.match(/.{1,64}/g)!.join('\n');
  return `-----BEGIN PUBLIC KEY-----\n${lines}\n-----END PUBLIC KEY-----`;
}

// ── Signing ───────────────────────────────────────────────────────

export async function signChallenge(privateKey: CryptoKey, challengeHex: string): Promise<string> {
  const data = new TextEncoder().encode(challengeHex);
  const sig  = await crypto.subtle.sign({ name: 'RSASSA-PKCS1-v1_5' }, privateKey, data);
  return toBase64URL(new Uint8Array(sig));
}

function toBase64URL(buf: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < buf.byteLength; i++) binary += String.fromCharCode(buf[i]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

// ── JWT parsing ───────────────────────────────────────────────────

function decodeJwtPart(part: string): Record<string, unknown> | null {
  const pad = part.length % 4 === 0 ? '' : '='.repeat(4 - (part.length % 4));
  const b64 = (part + pad).replace(/-/g, '+').replace(/_/g, '/');
  try {
    return JSON.parse(atob(b64)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function parseJwt(token: string): JwtParsed | null {
  const parts = token.split('.');
  if (parts.length < 2) return null;
  return {
    header:    decodeJwtPart(parts[0]) ?? {},
    payload:   decodeJwtPart(parts[1]) ?? {},
    signature: parts[2] ?? '',
    raw: { header: parts[0], payload: parts[1], signature: parts[2] ?? '' },
  };
}
