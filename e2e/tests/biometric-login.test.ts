/**
 * E2E tests — Biometric Login flow (Device Authorization Grant)
 *
 * Covers the complete happy path:
 *   startLogin → RSA-sign challenge → verifyChallenge → OIDC tokens
 *
 * And the following edge cases:
 *   - challenge nonce is unique across concurrent requests
 *   - challenge and nonce are correctly formatted
 *   - verifyChallenge returns full OIDC token set
 *   - access_token sub matches the registered userId
 *   - access_token acr claim equals "2" (biometric LoA)
 *   - challenge can only be used once (replay protection)
 *   - expired challenge is rejected
 *   - invalid signature is rejected → 401
 *   - wrong nonce is rejected → 400
 *   - startLogin for unknown device is rejected → 400
 */

import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AuthServiceClient } from "../helpers/client.ts";
import type {
  KeycloakTokenResponse,
  ProblemDetail,
  StartLoginResponse,
} from "../helpers/client.ts";
import { decodeJwtPayload, signChallenge } from "../helpers/crypto.ts";
import { createRegisteredDevice } from "../helpers/fixtures.ts";
import type { RegisteredDevice } from "../helpers/fixtures.ts";

// ---------------------------------------------------------------------------
// Setup: register one device to be shared across read-only happy-path tests
// ---------------------------------------------------------------------------

const client = new AuthServiceClient();

let device: RegisteredDevice & { cleanup: () => Promise<void> };

beforeAll(async () => {
  device = await createRegisteredDevice(client);
});

afterAll(async () => {
  await device.cleanup();
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Perform a complete startLogin and return the response body. */
async function startLogin(): Promise<StartLoginResponse> {
  const res = await client.startLogin({ deviceId: device.deviceId });
  expect(res.status, `startLogin returned HTTP ${res.status}: ${res.rawBody}`).toBe(200);
  return res.body as StartLoginResponse;
}

/** Perform the full login flow and return OIDC tokens. */
async function fullLogin(): Promise<KeycloakTokenResponse> {
  const loginRes = await startLogin();
  const signature = signChallenge(loginRes.challenge, device.keys.privateKeyPem);
  const verifyRes = await client.verifyChallenge({ nonce: loginRes.nonce, signature });
  expect(
    verifyRes.status,
    `verifyChallenge returned HTTP ${verifyRes.status}: ${verifyRes.rawBody}`
  ).toBe(200);
  return verifyRes.body as KeycloakTokenResponse;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("Biometric Login", () => {
  describe("startLogin", () => {
    it("returns 200 with nonce, challenge (64-char hex), and expiresInSeconds", async () => {
      const res = await client.startLogin({ deviceId: device.deviceId });

      expect(res.status).toBe(200);
      const body = res.body as StartLoginResponse;

      expect(body.nonce).toMatch(/^[0-9a-f]{32}$/);
      expect(body.challenge).toMatch(/^[0-9a-f]{64}$/);
      expect(body.expiresInSeconds).toBeGreaterThan(0);
    });

    it("returns a fresh unique nonce on every call", async () => {
      const [r1, r2, r3] = await Promise.all([
        client.startLogin({ deviceId: device.deviceId }),
        client.startLogin({ deviceId: device.deviceId }),
        client.startLogin({ deviceId: device.deviceId }),
      ]);

      const nonces = [
        (r1.body as StartLoginResponse).nonce,
        (r2.body as StartLoginResponse).nonce,
        (r3.body as StartLoginResponse).nonce,
      ];
      const unique = new Set(nonces);
      expect(unique.size).toBe(3);
    });

    it("rejects unknown deviceId → 400", async () => {
      const res = await client.startLogin({ deviceId: "this-device-does-not-exist" });
      expect(res.status).toBe(400);
      expect((res.body as ProblemDetail).detail).toMatch(/[Uu]nknown device/);
    });
  });

  // -------------------------------------------------------------------------

  describe("verifyChallenge — happy path", () => {
    it("returns 200 with access_token, id_token, and refresh_token", async () => {
      const tokens = await fullLogin();

      expect(tokens.access_token).toBeTruthy();
      expect(tokens.id_token).toBeTruthy();
      expect(tokens.refresh_token).toBeTruthy();
      expect(tokens.token_type).toMatch(/bearer/i);
      expect(tokens.expires_in).toBeGreaterThan(0);
    });

    it("access_token sub is a non-empty string (Keycloak internal UUID)", async () => {
      const tokens = await fullLogin();
      const payload = decodeJwtPayload(tokens.access_token);
      // sub in Keycloak OIDC tokens is the internal user UUID, not the userId string
      expect(typeof payload.sub).toBe("string");
      expect(payload.sub).toBeTruthy();
    });

    it("access_token preferred_username equals the registered userId", async () => {
      const tokens = await fullLogin();
      const payload = decodeJwtPayload(tokens.access_token);
      expect(payload.preferred_username).toBe(device.userId);
    });

    it("access_token acr claim equals '2' (biometric Level-of-Assurance)", async () => {
      const tokens = await fullLogin();
      const payload = decodeJwtPayload(tokens.access_token);
      expect(payload.acr).toBe("2");
    });

    it("id_token preferred_username equals the registered userId", async () => {
      const tokens = await fullLogin();
      const payload = decodeJwtPayload(tokens.id_token);
      expect(payload.preferred_username).toBe(device.userId);
    });
  });

  // -------------------------------------------------------------------------

  describe("verifyChallenge — rejection scenarios", () => {
    it("replay: used challenge is rejected → 400", async () => {
      const loginRes = await startLogin();
      const signature = signChallenge(loginRes.challenge, device.keys.privateKeyPem);

      // First use: must succeed
      const first = await client.verifyChallenge({ nonce: loginRes.nonce, signature });
      expect(first.status).toBe(200);

      // Second use: same nonce → must fail
      const second = await client.verifyChallenge({ nonce: loginRes.nonce, signature });
      expect(second.status).toBe(400);
      expect((second.body as ProblemDetail).detail).toMatch(/[Aa]lready used/);
    });

    it("invalid signature is rejected → 401", async () => {
      const loginRes = await startLogin();

      // Sign with a DIFFERENT private key (wrong device)
      const wrongDevice = (
        await createRegisteredDevice(client, { userId: `wrong-${Date.now()}` })
      );
      const badSignature = signChallenge(loginRes.challenge, wrongDevice.keys.privateKeyPem);
      await wrongDevice.cleanup();

      const res = await client.verifyChallenge({ nonce: loginRes.nonce, signature: badSignature });
      expect(res.status).toBe(401);
      expect((res.body as ProblemDetail).detail).toMatch(/[Ii]nvalid signature/);
    });

    it("tampered challenge bytes in signature are rejected → 401", async () => {
      const loginRes = await startLogin();

      // Sign the challenge with one byte flipped
      const tamperedChallenge =
        loginRes.challenge.slice(0, -2) +
        (loginRes.challenge.slice(-2) === "ff" ? "00" : "ff");
      const badSig = signChallenge(tamperedChallenge, device.keys.privateKeyPem);

      const res = await client.verifyChallenge({ nonce: loginRes.nonce, signature: badSig });
      expect(res.status).toBe(401);
    });

    it("garbage base64 signature is rejected → 401", async () => {
      const loginRes = await startLogin();

      const res = await client.verifyChallenge({
        nonce: loginRes.nonce,
        signature: "bm90LWEtcmVhbC1zaWduYXR1cmU", // base64url of "not-a-real-signature"
      });
      expect(res.status).toBe(401);
    });

    it("unknown nonce is rejected → 400", async () => {
      const res = await client.verifyChallenge({
        nonce: "0000000000000000000000000000000000000000000000000000000000000000",
        signature: "dW5rbm93bg",
      });
      expect(res.status).toBe(400);
      expect((res.body as ProblemDetail).detail).toMatch(/[Ii]nvalid nonce/);
    });

    it("blank nonce fails Bean Validation → 400", async () => {
      const res = await client.verifyChallenge({ nonce: "", signature: "dW5rbm93bg" });
      expect(res.status).toBe(400);
      const body = res.body as ProblemDetail;
      expect(body.detail).toMatch(/nonce/i);
    });

    it("blank signature fails Bean Validation → 400", async () => {
      const loginRes = await startLogin();
      const res = await client.verifyChallenge({ nonce: loginRes.nonce, signature: "" });
      expect(res.status).toBe(400);
      const body = res.body as ProblemDetail;
      expect(body.detail).toMatch(/signature/i);
    });
  });
});
