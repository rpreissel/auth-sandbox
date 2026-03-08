import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execSync } from 'node:child_process';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const REPO_ROOT = path.resolve(__dirname, '..');

function parseDotEnv(filePath: string): Record<string, string> {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Required .env file not found at ${filePath}`);
  }
  const result: Record<string, string> = {};
  const lines = fs.readFileSync(filePath, 'utf8').split('\n');
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eqIdx = trimmed.indexOf('=');
    if (eqIdx === -1) continue;
    const key = trimmed.slice(0, eqIdx).trim();
    const value = trimmed
      .slice(eqIdx + 1)
      .trim()
      .replace(/^["']|["']$/g, '');
    result[key] = value;
  }
  return result;
}

async function globalSetup() {
  const caCertPath = path.join(REPO_ROOT, 'certs', 'caddy-root.crt');
  const caddyContainer = 'auth-sandbox-caddy-1';
  
  try {
    const caddyCaBytes = execSync(
      `podman exec ${caddyContainer} cat /data/caddy/pki/authorities/local/root.crt`,
      { stdio: ['pipe', 'pipe', 'pipe'] }
    );
    fs.mkdirSync(path.dirname(caCertPath), { recursive: true });
    fs.writeFileSync(caCertPath, caddyCaBytes);
    console.log('[playwright] Caddy root CA written to', caCertPath);
  } catch (err) {
    console.warn('[playwright] Could not extract Caddy CA:', err);
  }

  const envVars = parseDotEnv(path.join(REPO_ROOT, '.env'));

  // Override keycloak URL to use localhost instead of internal hostname
  const keycloakUrl = 'https://keycloak.localhost:8443';

  process.env['E2E_AUTH_BASE'] = 'https://auth-service.localhost:8443';
  process.env['E2E_KEYCLOAK_BASE'] = keycloakUrl;
  process.env['E2E_ADMIN_USERNAME'] = 'admin';
  process.env['E2E_ADMIN_PASSWORD'] = envVars['KEYCLOAK_ADMIN_PASSWORD'] ?? 'admin-password';
  process.env['E2E_CA_CERT_PATH'] = caCertPath;
  process.env['KEYCLOAK_ADMIN_TOKEN_ENDPOINT'] = keycloakUrl + '/realms/auth-sandbox/protocol/openid-connect/token';
}

export default globalSetup;
