/**
 * RSA key-pair utilities for E2E tests.
 *
 * The "device" in E2E tests is simulated: we generate an RSA key pair,
 * register the public key, and sign challenges with the private key —
 * exactly what a real mobile device keystore would do.
 *
 * Uses Node's built-in `crypto` module (no external dependencies).
 */

import { createSign, generateKeyPairSync } from "node:crypto";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface DeviceKeyPair {
  /** PKCS#8 PEM — stored in auth-service as the device's public key. */
  publicKeyPem: string;
  /** PKCS#8 PEM — kept in the test, never leaves the "device". */
  privateKeyPem: string;
}

// ---------------------------------------------------------------------------
// Key generation
// ---------------------------------------------------------------------------

/**
 * Generate a fresh 2048-bit RSA key pair.
 * 2048 bits keeps test startup fast while matching the format expected by
 * `AuthService.verifySignature` (SHA256withRSA / PKCS#1).
 */
export function generateDeviceKeyPair(): DeviceKeyPair {
  const { publicKey, privateKey } = generateKeyPairSync("rsa", {
    modulusLength: 2048,
    publicKeyEncoding: { type: "spki", format: "pem" },
    privateKeyEncoding: { type: "pkcs8", format: "pem" },
  });
  return { publicKeyPem: publicKey, privateKeyPem: privateKey };
}

// ---------------------------------------------------------------------------
// Challenge signing
// ---------------------------------------------------------------------------

/**
 * Sign a challenge string with SHA256withRSA, return base64url-encoded signature.
 *
 * The challenge is treated as a UTF-8 string (hex-encoded bytes from the server)
 * and signed as-is — matching `AuthService.verifySignature`.
 */
export function signChallenge(
  challengeHex: string,
  privateKeyPem: string
): string {
  const sign = createSign("SHA256");
  sign.update(challengeHex, "utf8");
  const sigBuffer = sign.sign(privateKeyPem);
  // Base64URL without padding — matches what auth-service expects
  return sigBuffer
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

// ---------------------------------------------------------------------------
// JWT decode helper (no verification — only for asserting claims in tests)
// ---------------------------------------------------------------------------

export interface JwtPayload {
  sub?: string;
  iss?: string;
  aud?: string | string[];
  acr?: string;
  exp?: number;
  iat?: number;
  preferred_username?: string;
  [key: string]: unknown;
}

/**
 * Decode the payload of a JWT without verifying the signature.
 * Only use this in tests to assert token claims — never in production code.
 */
export function decodeJwtPayload(token: string): JwtPayload {
  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error(`Not a valid JWT: expected 3 parts, got ${parts.length}`);
  }
  const padded = parts[1]!.replace(/-/g, "+").replace(/_/g, "/");
  const json = Buffer.from(padded, "base64").toString("utf8");
  return JSON.parse(json) as JwtPayload;
}
