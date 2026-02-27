/**
 * E2E tests — Device Registration flow
 *
 * Covers:
 *  - Admin creates a registration code → 201, body contains id/userId/useCount
 *  - New code appears in list with useCount=0
 *  - Device registers successfully with valid credentials → 201
 *  - use count increments to 1 after registration
 *  - Second device can register with the same code (multi-use)
 *  - Duplicate deviceId is rejected → 400
 *  - Wrong activation code is rejected → 401
 *  - Unknown / expired userId is rejected → 400
 *  - Name mismatch is rejected → 400
 *  - Missing required fields are rejected by Bean Validation → 400
 *  - Admin can delete a device via DELETE /admin/devices/:id → 204
 *  - Admin can delete a registration code → 204
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { AuthServiceClient } from "../helpers/client.ts";
import type {
  AdminDeviceResponse,
  AdminRegistrationCodeResponse,
  ProblemDetail,
  RegisterDeviceResponse,
} from "../helpers/client.ts";
import { generateDeviceKeyPair } from "../helpers/crypto.ts";
import { createRegisteredDevice, uniqueId } from "../helpers/fixtures.ts";

// ---------------------------------------------------------------------------
// Shared client — reads base URL and admin credentials from process.env
// (set by global-setup.ts)
// ---------------------------------------------------------------------------

const client = new AuthServiceClient();

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("Device Registration", () => {
  describe("Admin: registration code management", () => {
    it("creates a registration code and returns 201 with expected fields", async () => {
      const userId = uniqueId("e2e-reg-user");
      const res = await client.createRegistrationCode({
        userId,
        name: "Test Device",
        activationCode: "test-secret",
      });

      expect(res.status).toBe(201);
      const body = res.body as AdminRegistrationCodeResponse;
      expect(body.id).toBeTruthy();
      expect(body.userId).toBe(userId);
      expect(body.useCount).toBe(0);
      expect(body.expiresAt).toBeTruthy();

      // cleanup
      await client.deleteRegistrationCode(body.id);
    });

    it("new code appears in the list with useCount=0", async () => {
      const userId = uniqueId("e2e-list-user");
      const createRes = await client.createRegistrationCode({
        userId,
        name: "List Test Device",
        activationCode: "list-secret",
      });
      const created = createRes.body as AdminRegistrationCodeResponse;

      const listRes = await client.listRegistrationCodes();
      expect(listRes.status).toBe(200);

      const codes = listRes.body as AdminRegistrationCodeResponse[];
      const found = codes.find((c) => c.id === created.id);
      expect(found).toBeDefined();
      expect(found!.useCount).toBe(0);

      await client.deleteRegistrationCode(created.id);
    });

    it("deletes a registration code → 204", async () => {
      const userId = uniqueId("e2e-del-code-user");
      const createRes = await client.createRegistrationCode({
        userId,
        name: "Delete Code Test",
        activationCode: "del-secret",
      });
      const created = createRes.body as AdminRegistrationCodeResponse;

      const deleteRes = await client.deleteRegistrationCode(created.id);
      expect(deleteRes.status).toBe(204);

      // Must no longer appear in list
      const listRes = await client.listRegistrationCodes();
      const codes = listRes.body as AdminRegistrationCodeResponse[];
      expect(codes.find((c) => c.id === created.id)).toBeUndefined();
    });
  });

  // -------------------------------------------------------------------------

  describe("Happy path: device registers successfully", () => {
    let device: Awaited<ReturnType<typeof createRegisteredDevice>>;

    beforeEach(async () => {
      device = await createRegisteredDevice(client);
    });

    afterEach(async () => {
      await device.cleanup();
    });

    it("returns 201 with deviceId and success message", async () => {
      // createRegisteredDevice already called registerDevice — verify the
      // device appears in the admin list as additional confirmation
      const listRes = await client.listDevices();
      expect(listRes.status).toBe(200);
      const devices = listRes.body as AdminDeviceResponse[];
      const found = devices.find((d) => d.deviceId === device.deviceId);
      expect(found).toBeDefined();
      expect(found!.userId).toBe(device.userId);
    });

    it("increments useCount to 1 on the registration code after device registers", async () => {
      const listRes = await client.listRegistrationCodes();
      const codes = listRes.body as AdminRegistrationCodeResponse[];
      const code = codes.find((c) => c.id === device.registrationCode.id);
      expect(code).toBeDefined();
      expect(code!.useCount).toBe(1);
    });

    it("admin can delete a registered device → 204", async () => {
      const listRes = await client.listDevices();
      const devices = listRes.body as AdminDeviceResponse[];
      const found = devices.find((d) => d.deviceId === device.deviceId);
      expect(found).toBeDefined();

      const deleteRes = await client.deleteDevice(found!.id);
      expect(deleteRes.status).toBe(204);

      // Verify it's gone
      const listRes2 = await client.listDevices();
      const devices2 = listRes2.body as AdminDeviceResponse[];
      expect(devices2.find((d) => d.deviceId === device.deviceId)).toBeUndefined();
    });
  });

  // -------------------------------------------------------------------------

  describe("Multi-use: same code registers multiple devices", () => {
    const userId = uniqueId("e2e-multi-user");
    const activationCode = uniqueId("multi-secret");
    let codeId: string;
    const deviceIds: string[] = [];

    beforeEach(async () => {
      // Create a fresh registration code for this test
      const res = await client.createRegistrationCode({
        userId,
        name: "Multi User",
        activationCode,
      });
      codeId = (res.body as AdminRegistrationCodeResponse).id;
    });

    afterEach(async () => {
      // Clean up all registered devices
      const listRes = await client.listDevices();
      const devices = listRes.body as AdminDeviceResponse[];
      for (const devId of deviceIds) {
        const found = devices.find((d) => d.deviceId === devId);
        if (found) await client.deleteDevice(found.id);
      }
      try {
        await client.deleteRegistrationCode(codeId);
      } catch {
        // May already be deleted by device cleanup
      }
      deviceIds.length = 0;
    });

    it("registers two devices under the same userId and increments useCount to 2", async () => {
      const keys1 = generateDeviceKeyPair();
      const keys2 = generateDeviceKeyPair();
      const dev1 = uniqueId("multi-dev");
      const dev2 = uniqueId("multi-dev");
      deviceIds.push(dev1, dev2);

      const r1 = await client.registerDevice({
        deviceId: dev1,
        userId,
        name: "Multi User",
        activationCode,
        publicKey: keys1.publicKeyPem,
      });
      expect(r1.status).toBe(201);

      const r2 = await client.registerDevice({
        deviceId: dev2,
        userId,
        name: "Multi User",
        activationCode,
        publicKey: keys2.publicKeyPem,
      });
      expect(r2.status).toBe(201);

      const listRes = await client.listRegistrationCodes();
      const codes = listRes.body as AdminRegistrationCodeResponse[];
      const code = codes.find((c) => c.id === codeId);
      expect(code?.useCount).toBe(2);
    });
  });

  // -------------------------------------------------------------------------

  describe("Rejection scenarios", () => {
    it("duplicate deviceId returns 400", async () => {
      // Setup: register a first device
      const device = await createRegisteredDevice(client);

      // Attempt to register the same deviceId under a different userId
      const userId2 = uniqueId("e2e-dup-user");
      const activationCode2 = uniqueId("dup-secret");
      const codeRes = await client.createRegistrationCode({
        userId: userId2,
        name: "Dup User",
        activationCode: activationCode2,
      });
      const code2 = codeRes.body as AdminRegistrationCodeResponse;

      const dupRes = await client.registerDevice({
        deviceId: device.deviceId, // same deviceId
        userId: userId2,
        name: "Dup User",
        activationCode: activationCode2,
        publicKey: generateDeviceKeyPair().publicKeyPem,
      });
      expect(dupRes.status).toBe(400);
      expect((dupRes.body as ProblemDetail).detail).toMatch(/already registered/i);

      await device.cleanup();
      await client.deleteRegistrationCode(code2.id);
    });

    it("wrong activation code returns 401", async () => {
      const userId = uniqueId("e2e-wrong-code-user");
      const codeRes = await client.createRegistrationCode({
        userId,
        name: "Wrong Code User",
        activationCode: "correct-secret",
      });
      const code = codeRes.body as AdminRegistrationCodeResponse;

      const res = await client.registerDevice({
        deviceId: uniqueId("e2e-dev"),
        userId,
        name: "Wrong Code User",
        activationCode: "wrong-secret",
        publicKey: generateDeviceKeyPair().publicKeyPem,
      });
      expect(res.status).toBe(401);

      await client.deleteRegistrationCode(code.id);
    });

    it("unknown userId returns 400 (same message as expired to prevent enumeration)", async () => {
      const res = await client.registerDevice({
        deviceId: uniqueId("e2e-dev"),
        userId: "this-user-does-not-exist",
        name: "Ghost",
        activationCode: "any",
        publicKey: generateDeviceKeyPair().publicKeyPem,
      });
      expect(res.status).toBe(400);
      expect((res.body as ProblemDetail).detail).toMatch(/Unknown userId/i);
    });

    it("name mismatch returns 400", async () => {
      const userId = uniqueId("e2e-name-mismatch-user");
      const codeRes = await client.createRegistrationCode({
        userId,
        name: "Real Name",
        activationCode: "name-secret",
      });
      const code = codeRes.body as AdminRegistrationCodeResponse;

      const res = await client.registerDevice({
        deviceId: uniqueId("e2e-dev"),
        userId,
        name: "Wrong Name",
        activationCode: "name-secret",
        publicKey: generateDeviceKeyPair().publicKeyPem,
      });
      expect(res.status).toBe(400);
      expect((res.body as ProblemDetail).detail).toMatch(/[Nn]ame/);

      await client.deleteRegistrationCode(code.id);
    });

    it("blank deviceId fails Bean Validation → 400", async () => {
      const res = await client.registerDevice({
        deviceId: "",
        userId: uniqueId("val-user"),
        name: "Validation Test",
        activationCode: "secret",
        publicKey: generateDeviceKeyPair().publicKeyPem,
      });
      expect(res.status).toBe(400);
      const body = res.body as ProblemDetail;
      expect(body.detail).toMatch(/deviceId/i);
    });

    it("blank publicKey fails Bean Validation → 400", async () => {
      const res = await client.registerDevice({
        deviceId: uniqueId("e2e-dev"),
        userId: uniqueId("val-user"),
        name: "Validation Test",
        activationCode: "secret",
        publicKey: "",
      });
      expect(res.status).toBe(400);
      const body = res.body as ProblemDetail;
      expect(body.detail).toMatch(/publicKey/i);
    });
  });
});
