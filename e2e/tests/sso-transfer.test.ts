/**
 * E2E tests — SSO Transfer Flow
 *
 * Tests the browser-based SSO transfer flow:
 *   initTransfer → redeem URL → Keycloak auth → callback
 *
 * Edge cases:
 *   - transfer session can only be redeemed once (single-use)
 *   - expired transfer session is rejected
 */

import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AuthServiceClient } from "../helpers/client.ts";
import type { ProblemDetail } from "../helpers/client.ts";
import { createRegisteredDevice } from "../helpers/fixtures.ts";
import type { RegisteredDevice } from "../helpers/fixtures.ts";

const client = new AuthServiceClient();

let device: RegisteredDevice & { cleanup: () => Promise<void> };
let accessToken: string;

beforeAll(async () => {
  device = await createRegisteredDevice(client);

  const loginRes = await client.startLogin({ deviceId: device.deviceId });
  expect(loginRes.status).toBe(200);

  const { signChallenge } = await import("../helpers/crypto.ts");
  const { default: crypto } = await import("../helpers/crypto.ts");
  const signature = signChallenge(
    (loginRes.body as { challenge: string }).challenge,
    device.keys.privateKeyPem
  );

  const verifyRes = await client.verifyChallenge({
    nonce: (loginRes.body as { nonce: string }).nonce,
    signature,
  });
  expect(verifyRes.status).toBe(200);

  accessToken = (verifyRes.body as { access_token: string }).access_token;
});

afterAll(async () => {
  await device.cleanup();
});

describe("SSO Transfer", () => {
  describe("initTransfer", () => {
    it("returns 200 with transferUrl and expiresInSeconds", async () => {
      const res = await client.initTransfer(
        accessToken,
        "https://target-app.localhost:8443"
      );

      expect(res.status).toBe(200);
      const body = res.body as { transferUrl: string; expiresInSeconds: number };
      expect(body.transferUrl).toContain("/api/v1/transfer/redeem?t=");
      expect(body.expiresInSeconds).toBeGreaterThan(0);
    });

    it("rejects invalid access token → 400", async () => {
      const res = await client.initTransfer(
        "invalid-token",
        "https://target-app.localhost:8443"
      );
      expect(res.status).toBe(400);
    });
  });

  describe("transfer session single-use", () => {
    it("transfer session can only be redeemed once", async () => {
      const initRes = await client.initTransfer(
        accessToken,
        "https://target-app.localhost:8443"
      );
      expect(initRes.status).toBe(200);

      const { transferUrl } = initRes.body as { transferUrl: string };
      const url = new URL(transferUrl);
      const transferToken = url.searchParams.get("t");
      expect(transferToken).toBeTruthy();

      const transferService = await import("../helpers/transfer.ts");
      const redeemRes1 = await transferService.redeemTransfer(transferToken!);
      expect(redeemRes1.status).toBe(302);

      const redeemRes2 = await transferService.redeemTransfer(transferToken!);
      expect(redeemRes2.status).toBe(400);
      expect((redeemRes2.body as ProblemDetail).detail).toMatch(
        /already been (used|consumed|redeemed)|expired/i
      );
    });
  });
});
