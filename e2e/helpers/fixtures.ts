/**
 * Shared test fixture builder for the E2E suite.
 *
 * Provides a single function `createRegisteredDevice` that:
 *  1. Generates a fresh RSA key pair (simulates mobile device keystore)
 *  2. Creates a registration code via the admin API
 *  3. Registers the device via the public API
 *
 * Returns all values needed by subsequent login / error tests.
 */

import { AuthServiceClient } from "./client.ts";
import type { AdminRegistrationCodeResponse } from "./client.ts";
import { generateDeviceKeyPair } from "./crypto.ts";
import type { DeviceKeyPair } from "./crypto.ts";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface RegisteredDevice {
  deviceId: string;
  userId: string;
  name: string;
  activationCode: string;
  keys: DeviceKeyPair;
  registrationCode: AdminRegistrationCodeResponse;
}

// ---------------------------------------------------------------------------
// Unique ID helpers
// ---------------------------------------------------------------------------

let counter = 0;

/** Generate a unique ID with a readable prefix to ease debugging. */
export function uniqueId(prefix: string): string {
  counter++;
  return `${prefix}-${Date.now()}-${counter}`;
}

// ---------------------------------------------------------------------------
// Fixture
// ---------------------------------------------------------------------------

/**
 * Create a Keycloak user + registration code and register a simulated device.
 * Cleans up the device and registration code in the returned `cleanup()`.
 */
export async function createRegisteredDevice(
  client: AuthServiceClient,
  opts: {
    userId?: string;
    name?: string;
    activationCode?: string;
  } = {}
): Promise<RegisteredDevice & { cleanup: () => Promise<void> }> {
  const userId = opts.userId ?? uniqueId("e2e-user");
  const name = opts.name ?? `E2E Device ${userId}`;
  const activationCode = opts.activationCode ?? uniqueId("secret");
  const deviceId = uniqueId("e2e-dev");
  const keys = generateDeviceKeyPair();

  // 1. Create registration code (admin)
  const codeRes = await client.createRegistrationCode({
    userId,
    name,
    activationCode,
  });
  if (codeRes.status !== 201) {
    throw new Error(
      `Failed to create registration code: HTTP ${codeRes.status} — ${codeRes.rawBody}`
    );
  }
  const registrationCode = codeRes.body as AdminRegistrationCodeResponse;

  // 2. Register device
  const regRes = await client.registerDevice({
    deviceId,
    userId,
    name,
    activationCode,
    publicKey: keys.publicKeyPem,
  });
  if (regRes.status !== 201) {
    throw new Error(
      `Failed to register device: HTTP ${regRes.status} — ${regRes.rawBody}`
    );
  }

  // 3. Find the internal UUID of the device for cleanup
  async function cleanup() {
    try {
      const devicesRes = await client.listDevices();
      const devices = devicesRes.body as import("./client.ts").AdminDeviceResponse[];
      const device = devices.find((d) => d.deviceId === deviceId);
      if (device) {
        await client.deleteDevice(device.id);
      }
    } catch {
      // Best-effort cleanup — don't fail the test on cleanup errors
    }
    try {
      await client.deleteRegistrationCode(registrationCode.id);
    } catch {
      // Best-effort
    }
  }

  return { deviceId, userId, name, activationCode, keys, registrationCode, cleanup };
}
