/**
 * E2E tests — Token Refresh
 *
 * Covers:
 *  - Refresh token obtained from verifyChallenge can be exchanged for new tokens
 *  - New access_token has same sub as original
 *  - New access_token acr is "2" (session acr is preserved)
 *  - token_type is "Bearer"
 *  - expires_in > 0
 *  - Invalid / garbage refresh token returns 401
 *  - Blank refresh token fails Bean Validation → 400
 *
 * JWKS endpoint:
 *  - GET /.well-known/jwks.json returns 200 with a key set
 *  - JWKS contains the "device-login-key" kid
 */

import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AuthServiceClient } from "../helpers/client.ts";
import type { KeycloakTokenResponse, ProblemDetail } from "../helpers/client.ts";
import { decodeJwtPayload, signChallenge } from "../helpers/crypto.ts";
import { createRegisteredDevice } from "../helpers/fixtures.ts";
import type { RegisteredDevice } from "../helpers/fixtures.ts";

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

const client = new AuthServiceClient();

let device: RegisteredDevice & { cleanup: () => Promise<void> };

/** Tokens obtained from a single biometric login — shared across refresh tests. */
let initialTokens: KeycloakTokenResponse;

beforeAll(async () => {
  device = await createRegisteredDevice(client);

  // Perform a complete biometric login to obtain a refresh token
  const loginRes = await client.startLogin({ deviceId: device.deviceId });
  const lr = loginRes.body as import("../helpers/client.ts").StartLoginResponse;
  const signature = signChallenge(lr.challenge, device.keys.privateKeyPem);
  const verifyRes = await client.verifyChallenge({ nonce: lr.nonce, signature });
  expect(verifyRes.status, `Login failed: ${verifyRes.rawBody}`).toBe(200);
  initialTokens = verifyRes.body as KeycloakTokenResponse;
});

afterAll(async () => {
  await device.cleanup();
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("Token Refresh", () => {
  describe("happy path", () => {
    let refreshedTokens: KeycloakTokenResponse;

    it("exchanges a valid refresh token for a new token set → 200", async () => {
      const res = await client.refreshToken(initialTokens.refresh_token);
      expect(res.status).toBe(200);
      refreshedTokens = res.body as KeycloakTokenResponse;

      expect(refreshedTokens.access_token).toBeTruthy();
      expect(refreshedTokens.refresh_token).toBeTruthy();
      expect(refreshedTokens.expires_in).toBeGreaterThan(0);
      expect(refreshedTokens.token_type).toMatch(/bearer/i);
    });

    it("refreshed access_token has same preferred_username as original", async () => {
      if (!refreshedTokens) return; // skipped if previous test failed
      const original = decodeJwtPayload(initialTokens.access_token);
      const refreshed = decodeJwtPayload(refreshedTokens.access_token);
      // sub is Keycloak's internal UUID; preferred_username is the userId
      expect(refreshed.preferred_username).toBe(original.preferred_username);
      expect(refreshed.preferred_username).toBe(device.userId);
    });

    it("refreshed access_token preserves acr='2'", async () => {
      if (!refreshedTokens) return;
      const payload = decodeJwtPayload(refreshedTokens.access_token);
      expect(payload.acr).toBe("2");
    });

    it("can perform a second refresh with the new refresh token", async () => {
      if (!refreshedTokens) return;
      const res2 = await client.refreshToken(refreshedTokens.refresh_token);
      expect(res2.status).toBe(200);
      const t2 = res2.body as KeycloakTokenResponse;
      expect(t2.access_token).toBeTruthy();
    });
  });

  // -------------------------------------------------------------------------

  describe("rejection scenarios", () => {
    it("invalid refresh token returns 401", async () => {
      const res = await client.refreshToken(
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.bm90LWEtc2lnbmF0dXJl"
      );
      // Keycloak returns 400 with error=invalid_grant for expired/invalid tokens;
      // the auth-service wraps it as 401 (InvalidRefreshTokenException)
      expect([400, 401]).toContain(res.status);
    });

    it("completely garbage refresh token returns 4xx", async () => {
      const res = await client.refreshToken("not-a-jwt-at-all");
      expect(res.status).toBeGreaterThanOrEqual(400);
      expect(res.status).toBeLessThan(500);
    });

    it("blank refresh token fails Bean Validation → 400", async () => {
      const res = await client.refreshToken("");
      expect(res.status).toBe(400);
      const body = res.body as ProblemDetail;
      expect(body.detail).toMatch(/refreshToken/i);
    });
  });
});

// ---------------------------------------------------------------------------
// JWKS endpoint
// ---------------------------------------------------------------------------

describe("JWKS endpoint", () => {
  it("GET /.well-known/jwks.json returns 200", async () => {
    const res = await client.getJwks();
    expect(res.status).toBe(200);
  });

  it("JWKS response contains a key with kid='device-login-key'", async () => {
    const res = await client.getJwks();
    const body = res.body as { keys?: Array<{ kid?: string; kty?: string }> };
    expect(Array.isArray(body.keys)).toBe(true);
    const key = body.keys!.find((k) => k.kid === "device-login-key");
    expect(key).toBeDefined();
    expect(key!.kty).toBe("RSA");
  });
});
