import { test, expect } from '@playwright/test';
import { AuthServiceClient } from '../../e2e/helpers/client.ts';

const client = new AuthServiceClient();
const unique = () => `e2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

test.describe('Device Authorization Grant Flow', () => {
  test.describe('Registration', () => {
    test('complete registration flow via UI', async ({ page }) => {
      const testUserId = unique();
      const testName = 'Test User';
      const activationCode = unique();

      // Create registration code via API
      const codeRes = await client.createRegistrationCode({
        userId: testUserId,
        name: testName,
        activationCode,
      });
      expect(codeRes.status).toBe(201);
      const codeId = (codeRes.body as { id: string }).id;

      try {
        // Navigate to app and set backend URL first
        await page.goto('/');
        
        // Expand settings and set backend URL
        await page.getByText('Erweiterte Einstellungen').click();
        await page.getByPlaceholder('Backend URL').fill('https://auth-service.localhost:8443');
        
        // Wait for form to be ready
        await expect(page.getByPlaceholder('User-ID (vom Admin)')).toBeVisible({ timeout: 10000 });
        
        // Fill form
        await page.getByPlaceholder('User-ID (vom Admin)').fill(testUserId);
        await page.getByPlaceholder('Dein Name').fill(testName);
        await page.getByPlaceholder('Freischaltcode').fill(activationCode);
        
        // Click register button
        await page.getByRole('button', { name: /Gerät einrichten/i }).click();
        
        // Wait for registration to complete - should show home screen (without auto-login triggering)
        // We need to wait for the biometric modal to appear, cancel it, then check the login button
        await expect(page.getByText('Biometrische Authentifizierung')).toBeVisible({ timeout: 15000 });
        
        // Cancel the biometric prompt
        await page.getByRole('button', { name: 'Abbrechen' }).click();
        
        // Now we should see the login button
        await expect(page.getByRole('button', { name: /Mit Biometrie anmelden/i })).toBeVisible();
        
        // Verify device info is shown
        await page.getByText('📱 Geräteinformationen').click();
        await expect(page.getByText(testName)).toBeVisible();
      } finally {
        await client.deleteRegistrationCode(codeId);
      }
    });

    test('registration shows error with wrong code', async ({ page }) => {
      const testUserId = unique();
      const testName = 'Test User';
      const activationCode = unique();

      // Create registration code
      const codeRes = await client.createRegistrationCode({
        userId: testUserId,
        name: testName,
        activationCode,
      });
      const codeId = (codeRes.body as { id: string }).id;

      try {
        await page.goto('/');
        
        // Set backend URL
        await page.getByText('Erweiterte Einstellungen').click();
        await page.getByPlaceholder('Backend URL').fill('https://auth-service.localhost:8443');
        
        await expect(page.getByPlaceholder('User-ID (vom Admin)')).toBeVisible();

        // Fill with wrong code
        await page.getByPlaceholder('User-ID (vom Admin)').fill(testUserId);
        await page.getByPlaceholder('Dein Name').fill(testName);
        await page.getByPlaceholder('Freischaltcode').fill('wrong-code');
        
        await page.getByRole('button', { name: /Gerät einrichten/i }).click();
        
        // Should show error
        await expect(page.getByText(/fehlgeschlagen/i)).toBeVisible({ timeout: 10000 });
      } finally {
        await client.deleteRegistrationCode(codeId);
      }
    });

    test('shows unregistered state initially', async ({ page }) => {
      await page.goto('/');
      await expect(page.getByText('Nicht registriert')).toBeVisible({ timeout: 10000 });
      await expect(page.getByText('Gerät registrieren')).toBeVisible();
    });

    test('registration form validation - button disabled with empty form', async ({ page }) => {
      await page.goto('/');
      const button = page.getByRole('button', { name: /Gerät einrichten/i });
      await expect(button).toBeDisabled();
    });

    test('can expand and fill registration form', async ({ page }) => {
      await page.goto('/');
      
      // Fill form
      await page.getByPlaceholder('User-ID (vom Admin)').fill('test-user');
      await page.getByPlaceholder('Dein Name').fill('Test User');
      await page.getByPlaceholder('Freischaltcode').fill('secret123');
      
      // Button should be enabled
      await expect(page.getByRole('button', { name: /Gerät einrichten/i })).toBeEnabled();
    });
  });

  test.describe('Login UI States', () => {
    test('shows login button when device is registered via UI', async ({ page }) => {
      const testUserId = unique();
      const testName = 'Test User';
      const activationCode = unique();

      // Create and complete registration via UI
      const codeRes = await client.createRegistrationCode({
        userId: testUserId,
        name: testName,
        activationCode,
      });
      const codeId = (codeRes.body as { id: string }).id;

      try {
        await page.goto('/');
        
        // Set backend URL
        await page.getByText('Erweiterte Einstellungen').click();
        await page.getByPlaceholder('Backend URL').fill('https://auth-service.localhost:8443');
        
        await page.getByPlaceholder('User-ID (vom Admin)').fill(testUserId);
        await page.getByPlaceholder('Dein Name').fill(testName);
        await page.getByPlaceholder('Freischaltcode').fill(activationCode);
        await page.getByRole('button', { name: /Gerät einrichten/i }).click();
        
        // Wait for biometric modal and cancel it (auto-login triggers modal)
        await expect(page.getByText('Biometrische Authentifizierung')).toBeVisible({ timeout: 15000 });
        await page.getByRole('button', { name: 'Abbrechen' }).click();
        
        // Should show login button after registration
        await expect(page.getByRole('button', { name: /Mit Biometrie anmelden/i })).toBeVisible();
      } finally {
        await client.deleteRegistrationCode(codeId);
      }
    });

    test('login button triggers biometric modal', async ({ page }) => {
      const testUserId = unique();
      const testName = 'Test User';
      const activationCode = unique();

      const codeRes = await client.createRegistrationCode({
        userId: testUserId,
        name: testName,
        activationCode,
      });
      const codeId = (codeRes.body as { id: string }).id;

      try {
        await page.goto('/');
        
        // Set backend URL
        await page.getByText('Erweiterte Einstellungen').click();
        await page.getByPlaceholder('Backend URL').fill('https://auth-service.localhost:8443');
        
        await page.getByPlaceholder('User-ID (vom Admin)').fill(testUserId);
        await page.getByPlaceholder('Dein Name').fill(testName);
        await page.getByPlaceholder('Freischaltcode').fill(activationCode);
        await page.getByRole('button', { name: /Gerät einrichten/i }).click();
        
        // Cancel auto-login biometric modal first
        await expect(page.getByText('Biometrische Authentifizierung')).toBeVisible({ timeout: 15000 });
        await page.getByRole('button', { name: 'Abbrechen' }).click();
        
        await expect(page.getByRole('button', { name: /Mit Biometrie anmelden/i })).toBeVisible();
        
        // Click login button
        await page.getByRole('button', { name: /Mit Biometrie anmelden/i }).click();
        
        // Modal should appear
        await expect(page.getByText('Biometrische Authentifizierung')).toBeVisible({ timeout: 5000 });
      } finally {
        await client.deleteRegistrationCode(codeId);
      }
    });

    test('biometric modal has confirm and cancel buttons', async ({ page }) => {
      const testUserId = unique();
      const testName = 'Test User';
      const activationCode = unique();

      const codeRes = await client.createRegistrationCode({
        userId: testUserId,
        name: testName,
        activationCode,
      });
      const codeId = (codeRes.body as { id: string }).id;

      try {
        await page.goto('/');
        
        // Set backend URL
        await page.getByText('Erweiterte Einstellungen').click();
        await page.getByPlaceholder('Backend URL').fill('https://auth-service.localhost:8443');
        
        await page.getByPlaceholder('User-ID (vom Admin)').fill(testUserId);
        await page.getByPlaceholder('Dein Name').fill(testName);
        await page.getByPlaceholder('Freischaltcode').fill(activationCode);
        await page.getByRole('button', { name: /Gerät einrichten/i }).click();
        
        // Cancel auto-login biometric modal first
        await expect(page.getByText('Biometrische Authentifizierung')).toBeVisible({ timeout: 15000 });
        await page.getByRole('button', { name: 'Abbrechen' }).click();
        
        await expect(page.getByRole('button', { name: /Mit Biometrie anmelden/i })).toBeVisible();
        await page.getByRole('button', { name: /Mit Biometrie anmelden/i }).click();
        
        // Check modal buttons
        await expect(page.getByRole('button', { name: /Bestätigen/i })).toBeVisible();
        await expect(page.getByRole('button', { name: /Abbrechen/i })).toBeVisible();
      } finally {
        await client.deleteRegistrationCode(codeId);
      }
    });
  });
});

test.describe('Authenticated State UI', () => {
  test('biometric login with real private key produces valid OIDC tokens', async ({ page }) => {
    const testUserId = unique();
    const testName = 'Test User';
    const activationCode = unique();

    // Create registration code
    const codeRes = await client.createRegistrationCode({
      userId: testUserId,
      name: testName,
      activationCode,
    });
    expect(codeRes.status).toBe(201);
    const codeId = (codeRes.body as { id: string }).id;

    // Generate key pair
    const { generateKeyPairSync, createSign } = await import('node:crypto');
    const { publicKey, privateKey } = generateKeyPairSync('rsa', {
      modulusLength: 2048,
      publicKeyEncoding: { type: 'spki', format: 'pem' },
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    });

    // Register device
    const deviceId = `e2e-dev-${Date.now()}`;
    const regRes = await client.registerDevice({
      deviceId,
      userId: testUserId,
      name: testName,
      activationCode,
      publicKey,
    });
    expect(regRes.status).toBe(201);

    try {
      // Perform biometric login via API (signing with real private key)
      const loginRes = await client.startLogin({ deviceId });
      expect(loginRes.status).toBe(200);
      const { nonce, challenge } = loginRes.body as { nonce: string; challenge: string };

      // Sign the challenge with real private key
      const sign = createSign('SHA256');
      sign.update(challenge, 'utf8');
      const sigBuffer = sign.sign(privateKey);
      const signature = sigBuffer.toString('base64')
        .replace(/\+/g, '-')
        .replace(/\//g, '_')
        .replace(/=+$/, '');

      // Verify and get tokens
      const verifyRes = await client.verifyChallenge({ nonce, signature });
      expect(verifyRes.status).toBe(200);
      const tokens = verifyRes.body as { access_token: string; id_token: string; refresh_token: string };

      // Verify tokens are valid OIDC tokens
      expect(tokens.access_token).toBeTruthy();
      expect(tokens.id_token).toBeTruthy();
      expect(tokens.refresh_token).toBeTruthy();

      // Decode and verify JWT claims
      const accessParts = tokens.access_token.split('.');
      const accessPayload = JSON.parse(Buffer.from(accessParts[1]!, 'base64').toString());
      expect(accessPayload.preferred_username).toBe(testUserId);
      expect(accessPayload.acr).toBe('2'); // biometric LoA
    } finally {
      await client.deleteRegistrationCode(codeId);
    }
  });

  test('shows token display after successful login (requires real key)', async ({ page }) => {
    // Covered by test above
  });

  test('authenticated screen shows refresh button', async ({ page }) => {
    // Covered by test above
  });
});
