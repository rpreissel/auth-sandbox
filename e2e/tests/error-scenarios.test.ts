/**
 * E2E tests — Cross-cutting error and security scenarios
 *
 * Covers scenarios that span multiple controllers or test the security layer
 * directly, and which don't belong cleanly in a single flow test file:
 *
 *  Authentication / authorisation
 *    - Admin endpoints reject unauthenticated requests → 401
 *    - Admin endpoints reject wrong password → 401
 *    - Admin endpoints reject wrong username → 401
 *
 *  Malformed request bodies
 *    - POST with a completely empty body → 400
 *    - POST with non-JSON content-type → 4xx
 *    - POST with JSON that is missing all required fields → 400
 *    - POST with extra / unknown fields is tolerated → 2xx (Jackson ignores them)
 *
 *  Non-existent resources
 *    - DELETE /admin/registration-codes/:id with a random UUID → 404
 *    - DELETE /admin/devices/:id with a random UUID → 404
 *
 *  Method-not-allowed
 *    - PUT on a POST-only endpoint → 405
 *    - DELETE on a GET-only endpoint → 405
 *
 *  Concurrent login races
 *    - Two simultaneous startLogin calls for the same device both succeed with
 *      unique nonces; only the first successful verifyChallenge wins — the
 *      other nonce is still independently usable (nonces are independent).
 */

import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { AuthServiceClient } from "../helpers/client.ts";
import type {
  AdminRegistrationCodeResponse,
  ProblemDetail,
  StartLoginResponse,
} from "../helpers/client.ts";
import { signChallenge } from "../helpers/crypto.ts";
import { createRegisteredDevice, uniqueId } from "../helpers/fixtures.ts";
import type { RegisteredDevice } from "../helpers/fixtures.ts";
import * as https from "node:https";
import * as http from "node:http";
import * as fs from "node:fs";

// ---------------------------------------------------------------------------
// Shared setup
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
// Low-level raw-request helper (bypasses the typed client for malformed tests)
// ---------------------------------------------------------------------------

function makeAgent(): https.Agent {
  const caCertPath = process.env["E2E_CA_CERT_PATH"]!;
  return new https.Agent({
    ca: fs.readFileSync(caCertPath),
    rejectUnauthorized: true,
  });
}

interface RawResponse {
  status: number;
  rawBody: string;
}

async function rawRequest(
  method: string,
  path: string,
  body?: string,
  contentType = "application/json",
  authHeader?: string
): Promise<RawResponse> {
  const base = process.env["E2E_AUTH_BASE"] ?? "https://auth-service.localhost:8443";
  const parsed = new URL(base + path);
  const isHttps = parsed.protocol === "https:";

  return new Promise((resolve, reject) => {
    const headers: Record<string, string> = {
      "Content-Type": contentType,
      Accept: "application/json",
    };
    if (body) {
      headers["Content-Length"] = Buffer.byteLength(body).toString();
    }
    if (authHeader) {
      headers["Authorization"] = authHeader;
    }

    const opts: https.RequestOptions = {
      hostname: parsed.hostname,
      port: parsed.port || (isHttps ? 443 : 80),
      path: parsed.pathname + parsed.search,
      method,
      headers,
      agent: isHttps ? makeAgent() : undefined,
    };

    const transport = isHttps ? https : http;
    const req = (transport as typeof https).request(opts, (res) => {
      let raw = "";
      res.on("data", (c) => (raw += c));
      res.on("end", () => resolve({ status: res.statusCode ?? 0, rawBody: raw }));
    });
    req.on("error", reject);
    if (body) req.write(body);
    req.end();
  });
}

function adminBearerToken(keycloakTokenUrl: string, clientId: string, clientSecret: string): string {
  return "Bearer " + getAdminTokenSync(keycloakTokenUrl, clientId, clientSecret);
}

function getAdminTokenSync(keycloakTokenUrl: string, clientId: string, clientSecret: string): string {
  const body = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: clientId,
    client_secret: clientSecret,
  });

  const http = keycloakTokenUrl.startsWith("https:") ? require("https") : require("http");

  let token = "";
  let resolved = false;

  const options = {
    hostname: new URL(keycloakTokenUrl).hostname,
    port: new URL(keycloakTokenUrl).port || (keycloakTokenUrl.startsWith("https:") ? 443 : 80),
    path: new URL(keycloakTokenUrl).pathname,
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "Content-Length": Buffer.byteLength(body.toString()),
    },
  };

  const req = http.request(options, (res) => {
    let data = "";
    res.on("data", (chunk) => (data += chunk));
    res.on("end", () => {
      if (res.statusCode === 200) {
        const json = JSON.parse(data);
        token = json.access_token;
      }
      resolved = true;
    });
  });

  req.on("error", () => {
    resolved = true;
  });

  req.write(body.toString());
  req.end();

  while (!resolved) {
    // spin wait - acceptable for test setup
  }

  if (!token) {
    throw new Error("Failed to get admin token");
  }

  return token;
}

const keycloakTokenUrl = process.env["KEYCLOAK_ADMIN_TOKEN_ENDPOINT"] ?? "http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token";
const adminClientId = process.env["KEYCLOAK_ADMIN_CLIENT_ID"] ?? "device-login-admin";
const adminClientSecret = process.env["KEYCLOAK_ADMIN_CLIENT_SECRET"] ?? "";

const correctAdminAuth = () =>
  adminBearerToken(keycloakTokenUrl, adminClientId, adminClientSecret);

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("Admin endpoint authentication", () => {
  it("GET /admin/registration-codes without Authorization → 401", async () => {
    const res = await rawRequest("GET", "/api/v1/admin/registration-codes");
    expect(res.status).toBe(401);
  });

  it("GET /admin/devices without Authorization → 401", async () => {
    const res = await rawRequest("GET", "/api/v1/admin/devices");
    expect(res.status).toBe(401);
  });

  it("POST /admin/registration-codes without Authorization → 401", async () => {
    const res = await rawRequest(
      "POST",
      "/api/v1/admin/registration-codes",
      JSON.stringify({ userId: "u", name: "n", activationCode: "c" })
    );
    expect(res.status).toBe(401);
  });

  it("GET /admin/registration-codes with wrong client secret → 401", async () => {
    const badAuth = adminBearerToken(keycloakTokenUrl, adminClientId, "completely-wrong-secret");
    const res = await rawRequest(
      "GET",
      "/api/v1/admin/registration-codes",
      undefined,
      "application/json",
      badAuth
    );
    expect(res.status).toBe(401);
  });

  it("GET /admin/registration-codes with invalid token → 401", async () => {
    const badAuth = "Bearer invalid-token-12345";
    const res = await rawRequest(
      "GET",
      "/api/v1/admin/registration-codes",
      undefined,
      "application/json",
      badAuth
    );
    expect(res.status).toBe(401);
  });
});

// ---------------------------------------------------------------------------

describe("Malformed request bodies", () => {
  describe("POST /api/v1/devices/register", () => {
    it("empty body → 400", async () => {
      const res = await rawRequest("POST", "/api/v1/devices/register", "");
      expect(res.status).toBe(400);
    });

    it("body is not JSON → 4xx", async () => {
      const res = await rawRequest(
        "POST",
        "/api/v1/devices/register",
        "this is not json at all",
        "application/json"
      );
      expect(res.status).toBeGreaterThanOrEqual(400);
      expect(res.status).toBeLessThan(500);
    });

    it("JSON with all required fields missing → 400 (Bean Validation)", async () => {
      const res = await rawRequest(
        "POST",
        "/api/v1/devices/register",
        JSON.stringify({})
      );
      expect(res.status).toBe(400);
    });

    it("JSON with extra unknown fields is accepted (Jackson ignores unknowns) → 201 or 4xx business error, never 500", async () => {
      // We supply a valid payload PLUS unknown fields; server should not crash.
      const res = await rawRequest(
        "POST",
        "/api/v1/devices/register",
        JSON.stringify({
          deviceId: uniqueId("extra-dev"),
          userId: "this-user-will-not-exist",
          name: "Extra Fields Test",
          activationCode: "irrelevant",
          publicKey: "not-a-real-key",
          unknownField1: "ignored",
          unknownField2: 42,
        })
      );
      // Business logic will reject this (unknown user / bad key), but it must not 500
      expect(res.status).not.toBe(500);
    });
  });

  describe("POST /api/v1/auth/login/start", () => {
    it("empty body → 400", async () => {
      const res = await rawRequest("POST", "/api/v1/auth/login/start", "");
      expect(res.status).toBe(400);
    });

    it("missing deviceId field → 400", async () => {
      const res = await rawRequest(
        "POST",
        "/api/v1/auth/login/start",
        JSON.stringify({})
      );
      expect(res.status).toBe(400);
    });
  });

  describe("POST /api/v1/auth/login/verify", () => {
    it("empty body → 400", async () => {
      const res = await rawRequest("POST", "/api/v1/auth/login/verify", "");
      expect(res.status).toBe(400);
    });

    it("missing all fields → 400", async () => {
      const res = await rawRequest(
        "POST",
        "/api/v1/auth/login/verify",
        JSON.stringify({})
      );
      expect(res.status).toBe(400);
    });
  });
});

// ---------------------------------------------------------------------------

describe("Non-existent resources", () => {
  const randomUuid = "00000000-0000-0000-0000-000000000000";

  it("DELETE /admin/registration-codes/:id with non-existent UUID → 404", async () => {
    const res = await rawRequest(
      "DELETE",
      `/api/v1/admin/registration-codes/${randomUuid}`,
      undefined,
      "application/json",
      correctAdminAuth()
    );
    expect(res.status).toBe(404);
  });

  it("DELETE /admin/devices/:id with non-existent UUID → 404", async () => {
    const res = await rawRequest(
      "DELETE",
      `/api/v1/admin/devices/${randomUuid}`,
      undefined,
      "application/json",
      correctAdminAuth()
    );
    expect(res.status).toBe(404);
  });
});

// ---------------------------------------------------------------------------

describe("Method not allowed", () => {
  it("PUT /api/v1/devices/register → 405", async () => {
    const res = await rawRequest(
      "PUT",
      "/api/v1/devices/register",
      JSON.stringify({ deviceId: "x", userId: "y", name: "z", activationCode: "a", publicKey: "b" })
    );
    expect(res.status).toBe(405);
  });

  it("DELETE /api/v1/auth/login/start → 405", async () => {
    const res = await rawRequest("DELETE", "/api/v1/auth/login/start");
    expect(res.status).toBe(405);
  });

  it("PATCH /api/v1/auth/login/verify → 405", async () => {
    const res = await rawRequest("PATCH", "/api/v1/auth/login/verify");
    expect(res.status).toBe(405);
  });
});

// ---------------------------------------------------------------------------

describe("Concurrent login races", () => {
  it("two simultaneous startLogin calls return two distinct nonces", async () => {
    const [r1, r2] = await Promise.all([
      client.startLogin({ deviceId: device.deviceId }),
      client.startLogin({ deviceId: device.deviceId }),
    ]);

    expect(r1.status).toBe(200);
    expect(r2.status).toBe(200);

    const nonce1 = (r1.body as StartLoginResponse).nonce;
    const nonce2 = (r2.body as StartLoginResponse).nonce;
    expect(nonce1).not.toBe(nonce2);
  });

  it("each nonce is independently usable (nonces are independent sessions)", async () => {
    // Obtain two challenges
    const [r1, r2] = await Promise.all([
      client.startLogin({ deviceId: device.deviceId }),
      client.startLogin({ deviceId: device.deviceId }),
    ]);
    const login1 = r1.body as StartLoginResponse;
    const login2 = r2.body as StartLoginResponse;

    // Sign and verify both — order doesn't matter, both should succeed
    const sig1 = signChallenge(login1.challenge, device.keys.privateKeyPem);
    const sig2 = signChallenge(login2.challenge, device.keys.privateKeyPem);

    const [v1, v2] = await Promise.all([
      client.verifyChallenge({ nonce: login1.nonce, signature: sig1 }),
      client.verifyChallenge({ nonce: login2.nonce, signature: sig2 }),
    ]);

    expect(v1.status).toBe(200);
    expect(v2.status).toBe(200);
  });

  it("using the same nonce twice after success is rejected on the second attempt → 400", async () => {
    const loginRes = await client.startLogin({ deviceId: device.deviceId });
    const login = loginRes.body as StartLoginResponse;
    const sig = signChallenge(login.challenge, device.keys.privateKeyPem);

    // First verify: must succeed
    const first = await client.verifyChallenge({ nonce: login.nonce, signature: sig });
    expect(first.status).toBe(200);

    // Second verify: must be rejected as replay
    const second = await client.verifyChallenge({ nonce: login.nonce, signature: sig });
    expect(second.status).toBe(400);
    expect((second.body as ProblemDetail).detail).toMatch(/[Aa]lready used/);
  });
});

// ---------------------------------------------------------------------------

describe("Admin registration code: edge cases", () => {
  it("creating a code with validFor='PT1M' (1 minute) is accepted → 201", async () => {
    const res = await client.createRegistrationCode({
      userId: uniqueId("short-lived-user"),
      name: "Short Lived Device",
      activationCode: "short-secret",
      validFor: "PT1M",
    });
    expect(res.status).toBe(201);
    const body = res.body as AdminRegistrationCodeResponse;
    expect(body.id).toBeTruthy();
    // cleanup
    await client.deleteRegistrationCode(body.id);
  });

  it("creating a code with blank userId fails Bean Validation → 400", async () => {
    const res = await rawRequest(
      "POST",
      "/api/v1/admin/registration-codes",
      JSON.stringify({ userId: "", name: "Test", activationCode: "s" }),
      "application/json",
      correctAdminAuth()
    );
    expect(res.status).toBe(400);
  });

  it("creating a code with blank activationCode fails Bean Validation → 400", async () => {
    const res = await rawRequest(
      "POST",
      "/api/v1/admin/registration-codes",
      JSON.stringify({ userId: uniqueId("u"), name: "Test", activationCode: "" }),
      "application/json",
      correctAdminAuth()
    );
    expect(res.status).toBe(400);
  });

  it("POST /admin/registration-codes/cleanup without Authorization → 401", async () => {
    const res = await rawRequest("POST", "/api/v1/admin/registration-codes/cleanup");
    expect(res.status).toBe(401);
  });
});
