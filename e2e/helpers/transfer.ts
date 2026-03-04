/**
 * Helper functions for SSO Transfer flow testing.
 */

import * as https from "node:https";
import * as fs from "node:fs";

function makeAgent(): https.Agent {
  const caCertPath = process.env["E2E_CA_CERT_PATH"];
  if (!caCertPath || !fs.existsSync(caCertPath)) {
    throw new Error(
      `E2E_CA_CERT_PATH not set or file missing: ${caCertPath}`
    );
  }
  return new https.Agent({
    ca: fs.readFileSync(caCertPath),
    rejectUnauthorized: true,
  });
}

interface HttpResponse<T> {
  status: number;
  body: T;
  rawBody: string;
}

async function request<T>(
  method: string,
  url: string,
  body?: unknown
): Promise<HttpResponse<T>> {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);

    const reqHeaders: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "application/json",
    };

    const bodyStr = body !== undefined ? JSON.stringify(body) : undefined;
    if (bodyStr) {
      reqHeaders["Content-Length"] = Buffer.byteLength(bodyStr).toString();
    }

    const options: https.RequestOptions = {
      hostname: parsed.hostname,
      port: parsed.port || 443,
      path: parsed.pathname + parsed.search,
      method,
      headers: reqHeaders,
      agent: makeAgent(),
    };

    const req = https.request(options, (res) => {
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

const AUTH_BASE = process.env["E2E_AUTH_BASE"] ?? "https://auth-service.localhost:8443";

export async function redeemTransfer(
  transferToken: string
): Promise<HttpResponse<unknown>> {
  return request("GET", `${AUTH_BASE}/api/v1/transfer/redeem?t=${transferToken}`);
}
