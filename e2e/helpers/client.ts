/**
 * Typed HTTP client for the auth-service API.
 *
 * Uses Node's built-in `https` module with the project CA certificate so that
 * Caddy's `tls internal` certificate is trusted without disabling TLS entirely.
 */

import * as fs from "node:fs";
import * as https from "node:https";
import * as http from "node:http";

// ---------------------------------------------------------------------------
// Types — mirror the auth-service DTOs exactly
// ---------------------------------------------------------------------------

export interface RegisterDeviceRequest {
  deviceId: string;
  userId: string;
  name: string;
  activationCode: string;
  publicKey: string;
}

export interface RegisterDeviceResponse {
  deviceId: string;
  message: string;
}

export interface StartLoginRequest {
  deviceId: string;
}

export interface StartLoginResponse {
  nonce: string;
  challenge: string;
  expiresInSeconds: number;
}

export interface VerifyChallengeRequest {
  nonce: string;
  signature: string;
}

export interface KeycloakTokenResponse {
  access_token: string;
  id_token: string;
  refresh_token: string;
  expires_in: number;
  token_type: string;
  scope: string;
}

export interface CreateRegistrationCodeRequest {
  userId: string;
  name: string;
  activationCode: string;
  /** ISO-8601 duration, e.g. "PT1H". Optional — defaults to PT24H on server. */
  validFor?: string;
}

export interface AdminRegistrationCodeResponse {
  id: string;
  userId: string;
  name: string;
  useCount: number;
  expiresAt: string;
}

export interface AdminDeviceResponse {
  id: string;
  deviceId: string;
  userId: string;
  name: string;
  createdAt: string;
}

export interface ProblemDetail {
  title: string;
  detail: string;
  status: number;
}

// ---------------------------------------------------------------------------
// HTTP helper
// ---------------------------------------------------------------------------

interface HttpResponse<T> {
  status: number;
  body: T;
  rawBody: string;
}

function makeAgent(): https.Agent {
  const caCertPath = process.env["E2E_CA_CERT_PATH"];
  if (!caCertPath || !fs.existsSync(caCertPath)) {
    throw new Error(
      `E2E_CA_CERT_PATH not set or file missing: ${caCertPath}.\n` +
        "Did the global setup run successfully?"
    );
  }
  return new https.Agent({
    ca: fs.readFileSync(caCertPath),
    rejectUnauthorized: true,
  });
}

async function request<T>(
  method: string,
  url: string,
  body?: unknown,
  headers: Record<string, string> = {}
): Promise<HttpResponse<T>> {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const isHttps = parsed.protocol === "https:";

    const reqHeaders: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json",
      ...headers,
    };

    const bodyStr = body !== undefined ? JSON.stringify(body) : undefined;
    if (bodyStr) {
      reqHeaders["Content-Length"] = Buffer.byteLength(bodyStr).toString();
    }

    const options: https.RequestOptions = {
      hostname: parsed.hostname,
      port: parsed.port || (isHttps ? 443 : 80),
      path: parsed.pathname + parsed.search,
      method,
      headers: reqHeaders,
      agent: isHttps ? makeAgent() : undefined,
    };

    const transport = isHttps ? https : http;
    const req = (transport as typeof https).request(options, (res) => {
      let raw = "";
      res.on("data", (chunk) => (raw += chunk));
      res.on("end", () => {
        let parsed: T;
        try {
          parsed = JSON.parse(raw) as T;
        } catch {
          parsed = raw as unknown as T;
        }
        resolve({
          status: res.statusCode ?? 0,
          body: parsed,
          rawBody: raw,
        });
      });
    });

    req.on("error", reject);
    if (bodyStr) req.write(bodyStr);
    req.end();
  });
}

// ---------------------------------------------------------------------------
// Auth-service API client
// ---------------------------------------------------------------------------

export class AuthServiceClient {
  private readonly base: string;
  private readonly adminAuth: string;

  constructor(
    base = process.env["E2E_AUTH_BASE"] ?? "https://auth-service.localhost:8443",
    adminUsername = process.env["E2E_ADMIN_USERNAME"] ?? "admin",
    adminPassword = process.env["E2E_ADMIN_PASSWORD"] ?? ""
  ) {
    this.base = base;
    this.adminAuth =
      "Basic " +
      Buffer.from(`${adminUsername}:${adminPassword}`).toString("base64");
  }

  // --- Admin ------------------------------------------------------------------

  async createRegistrationCode(
    req: CreateRegistrationCodeRequest
  ): Promise<HttpResponse<AdminRegistrationCodeResponse>> {
    return request<AdminRegistrationCodeResponse>(
      "POST",
      `${this.base}/api/v1/admin/registration-codes`,
      req,
      { Authorization: this.adminAuth }
    );
  }

  async listRegistrationCodes(): Promise<
    HttpResponse<AdminRegistrationCodeResponse[]>
  > {
    return request<AdminRegistrationCodeResponse[]>(
      "GET",
      `${this.base}/api/v1/admin/registration-codes`,
      undefined,
      { Authorization: this.adminAuth }
    );
  }

  async deleteRegistrationCode(
    id: string
  ): Promise<HttpResponse<unknown>> {
    return request<unknown>(
      "DELETE",
      `${this.base}/api/v1/admin/registration-codes/${id}`,
      undefined,
      { Authorization: this.adminAuth }
    );
  }

  async listDevices(): Promise<HttpResponse<AdminDeviceResponse[]>> {
    return request<AdminDeviceResponse[]>(
      "GET",
      `${this.base}/api/v1/admin/devices`,
      undefined,
      { Authorization: this.adminAuth }
    );
  }

  async deleteDevice(id: string): Promise<HttpResponse<unknown>> {
    return request<unknown>(
      "DELETE",
      `${this.base}/api/v1/admin/devices/${id}`,
      undefined,
      { Authorization: this.adminAuth }
    );
  }

  // --- Device registration ----------------------------------------------------

  async registerDevice(
    req: RegisterDeviceRequest
  ): Promise<HttpResponse<RegisterDeviceResponse | ProblemDetail>> {
    return request<RegisterDeviceResponse | ProblemDetail>(
      "POST",
      `${this.base}/api/v1/devices/register`,
      req
    );
  }

  // --- Auth flow --------------------------------------------------------------

  async startLogin(
    req: StartLoginRequest
  ): Promise<HttpResponse<StartLoginResponse | ProblemDetail>> {
    return request<StartLoginResponse | ProblemDetail>(
      "POST",
      `${this.base}/api/v1/auth/login/start`,
      req
    );
  }

  async verifyChallenge(
    req: VerifyChallengeRequest
  ): Promise<HttpResponse<KeycloakTokenResponse | ProblemDetail>> {
    return request<KeycloakTokenResponse | ProblemDetail>(
      "POST",
      `${this.base}/api/v1/auth/login/verify`,
      req
    );
  }

  async refreshToken(
    refreshToken: string
  ): Promise<HttpResponse<KeycloakTokenResponse | ProblemDetail>> {
    return request<KeycloakTokenResponse | ProblemDetail>(
      "POST",
      `${this.base}/api/v1/auth/token/refresh`,
      { refreshToken }
    );
  }

  async getJwks(): Promise<HttpResponse<Record<string, unknown>>> {
    return request<Record<string, unknown>>(
      "GET",
      `${this.base}/api/v1/auth/.well-known/jwks.json`
    );
  }
}
