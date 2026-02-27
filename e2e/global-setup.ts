/**
 * Vitest global setup — validates that the compose stack is running and
 * ensures the Keycloak realm is provisioned via `tofu apply`.
 *
 * Prerequisites (must be done manually before running tests):
 *  - Stack running: `podman compose up -d` (or `docker compose up -d`)
 *  - Certs generated: `./generate-certs.sh`
 *  - auth-service JAR built: `cd auth-service && ./gradlew bootJar`
 *  - keycloak-extension JAR built: `cd keycloak-extension && ./gradlew jar`
 *  - .env file present at repo root
 *
 * What this setup does:
 *  1. Parse .env → inject into process.env
 *  2. Export well-known env vars (E2E_AUTH_BASE, E2E_CA_CERT_PATH, etc.)
 *  3. Wait for auth-service and Keycloak health endpoints
 *  4. Regenerate tofu/terraform.tfvars from .env values
 *  5. Run `tofu apply -auto-approve` to provision / reconcile the realm
 */

import { execSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import * as https from "node:https";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Absolute path to the repository root (one level above e2e/) */
const REPO_ROOT = path.resolve(import.meta.dirname, "..");

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Parse a .env file into a key→value record (skips comments / blank lines). */
function parseDotEnv(filePath: string): Record<string, string> {
  if (!fs.existsSync(filePath)) {
    throw new Error(
      `Required .env file not found at ${filePath}.\n` +
        "Copy .env.example to .env and fill in the secrets."
    );
  }
  const result: Record<string, string> = {};
  const lines = fs.readFileSync(filePath, "utf8").split("\n");
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const eqIdx = trimmed.indexOf("=");
    if (eqIdx === -1) continue;
    const key = trimmed.slice(0, eqIdx).trim();
    const value = trimmed
      .slice(eqIdx + 1)
      .trim()
      .replace(/^["']|["']$/g, "");
    result[key] = value;
  }
  return result;
}

/** Wait until a URL returns HTTP < 500 (polling every 2 s, up to maxMs). */
async function waitForHttp(
  url: string,
  caCertPath: string,
  maxMs = 60_000
): Promise<void> {
  const deadline = Date.now() + maxMs;
  const ca = fs.readFileSync(caCertPath);

  while (Date.now() < deadline) {
    const ok = await new Promise<boolean>((resolve) => {
      const req = https.get(url, { ca, rejectUnauthorized: true }, (res) => {
        resolve((res.statusCode ?? 0) < 500);
        res.resume(); // drain
      });
      req.on("error", () => resolve(false));
      req.setTimeout(4_000, () => {
        req.destroy();
        resolve(false);
      });
    });
    if (ok) return;
    await new Promise((r) => setTimeout(r, 2_000));
  }
  throw new Error(`Service at ${url} did not become ready within ${maxMs} ms`);
}

// ---------------------------------------------------------------------------
// Global setup entry point
// ---------------------------------------------------------------------------

export async function setup(): Promise<void> {
  console.log("\n[e2e global-setup] Starting…");

  // 1. Load .env ---------------------------------------------------------------
  const envVars = parseDotEnv(path.join(REPO_ROOT, ".env"));
  Object.assign(process.env, envVars);

  // Expose well-known vars for test files
  process.env["E2E_AUTH_BASE"] = "https://auth-service.localhost:8443";
  process.env["E2E_KEYCLOAK_BASE"] = "https://keycloak.localhost:8443";
  process.env["E2E_ADMIN_USERNAME"] = "admin";
  process.env["E2E_ADMIN_PASSWORD"] =
    envVars["KEYCLOAK_ADMIN_PASSWORD"] ?? "admin";
  process.env["E2E_CA_CERT_PATH"] = path.join(REPO_ROOT, "certs", "caddy-root.crt");

  const caCertPath = process.env["E2E_CA_CERT_PATH"]!;
  const authBase = process.env["E2E_AUTH_BASE"]!;
  const keycloakBase = process.env["E2E_KEYCLOAK_BASE"]!;

  // 2. Extract Caddy root CA from the running container ----------------------
  // Caddy uses `tls internal` with its own generated CA (not certs/ca.crt).
  // We extract it at test startup so Node can trust the TLS certificates.
  const caddyCaInContainer = "/data/caddy/pki/authorities/local/root.crt";
  const caddyContainer = "auth-sandbox-caddy-1";
  try {
    const caddyCaBytes = execSync(
      `docker exec ${caddyContainer} cat ${caddyCaInContainer}`,
      { stdio: ["pipe", "pipe", "pipe"] }
    );
    fs.mkdirSync(path.dirname(caCertPath), { recursive: true });
    fs.writeFileSync(caCertPath, caddyCaBytes);
    console.log(`[e2e global-setup] Caddy root CA written to ${caCertPath}`);
  } catch (err) {
    // Fallback: try the pre-generated certs/ca.crt (may not match)
    if (!fs.existsSync(caCertPath)) {
      throw new Error(
        `Could not extract Caddy CA and ${caCertPath} does not exist.\n` +
          `Run: docker exec ${caddyContainer} cat ${caddyCaInContainer} > certs/caddy-root.crt`
      );
    }
    console.warn("[e2e global-setup] Could not extract Caddy CA; using existing certs/ca.crt");
  }

  // 3. Wait for stack health ---------------------------------------------------
  console.log("[e2e global-setup] Waiting for auth-service health…");
  await waitForHttp(`${authBase}/actuator/health`, caCertPath, 60_000);
  console.log("[e2e global-setup] auth-service is healthy.");

  console.log("[e2e global-setup] Waiting for Keycloak…");
  await waitForHttp(`${keycloakBase}/realms/master`, caCertPath, 60_000);
  console.log("[e2e global-setup] Keycloak is reachable.");

  // 4. Regenerate terraform.tfvars ---------------------------------------------
  const tofuDir = path.join(REPO_ROOT, "tofu");
  const tfvarsContent = [
    `keycloak_admin_password          = ${JSON.stringify(envVars["KEYCLOAK_ADMIN_PASSWORD"] ?? "")}`,
    `device_login_client_secret       = ${JSON.stringify(envVars["KEYCLOAK_CLIENT_SECRET"] ?? "")}`,
    `device_login_admin_client_secret = ${JSON.stringify(envVars["KEYCLOAK_ADMIN_CLIENT_SECRET"] ?? "")}`,
    `device_login_jwks_url            = "http://auth-service:8083/api/v1/auth/.well-known/jwks.json"`,
    `keycloak_url                     = "https://keycloak.localhost:8443"`,
    `jwt_issuer                       = ${JSON.stringify(envVars["JWT_ISSUER"] ?? "https://auth-service.localhost:8443")}`,
  ].join("\n");

  fs.writeFileSync(path.join(tofuDir, "terraform.tfvars"), tfvarsContent, "utf8");

  // 5. tofu init + apply -------------------------------------------------------
  // tofu apply ensures the Keycloak realm is fully provisioned.
  // If apply fails (e.g. due to provider API drift) but the realm is already
  // reachable, we log a warning and continue — tests can still run against
  // the existing configuration.
  console.log("[e2e global-setup] Running tofu init + apply…");
  try {
    execSync("tofu init -input=false", {
      cwd: tofuDir,
      stdio: "pipe",
      env: { ...process.env, CHECKPOINT_DISABLE: "1" },
    });
    execSync("tofu apply -auto-approve -input=false", {
      cwd: tofuDir,
      stdio: "inherit",
      env: { ...process.env, CHECKPOINT_DISABLE: "1" },
    });
    console.log("[e2e global-setup] Realm provisioned.");
  } catch (err) {
    // If the realm is already reachable, warn but don't abort — the existing
    // realm configuration is sufficient for the device-login E2E tests.
    const realmReachable = await new Promise<boolean>((resolve) => {
      const ca = fs.readFileSync(caCertPath);
      const req = https.get(
        `${keycloakBase}/realms/auth-sandbox/.well-known/openid-configuration`,
        { ca, rejectUnauthorized: true },
        (res) => { resolve((res.statusCode ?? 0) < 400); res.resume(); }
      );
      req.on("error", () => resolve(false));
      req.setTimeout(5_000, () => { req.destroy(); resolve(false); });
    });

    if (realmReachable) {
      console.warn(
        "[e2e global-setup] WARNING: tofu apply failed but the realm is already reachable.\n" +
          "                   Continuing with the existing Keycloak configuration.\n" +
          "                   Run `tofu apply` manually to fix the provider issue."
      );
    } else {
      console.error("[e2e global-setup] tofu apply failed and realm is not reachable:", err);
      throw err;
    }
  }

  console.log("[e2e global-setup] Realm provisioned. Tests can start.\n");
}

export async function teardown(): Promise<void> {
  // Nothing to tear down — the stack is managed externally.
  console.log("\n[e2e global-setup] Teardown complete (stack is externally managed).");
}
