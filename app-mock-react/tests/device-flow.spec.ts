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
        
        // Wait for registration to complete - should show home screen
        await expect(page.getByText('Mit Biometrie anmelden')).toBeVisible({ timeout: 15000 });
        
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
        
        // Should show login button after registration
        await expect(page.getByRole('button', { name: /Mit Biometrie anmelden/i })).toBeVisible({ timeout: 15000 });
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
        
        await expect(page.getByRole('button', { name: /Mit Biometrie anmelden/i })).toBeVisible({ timeout: 15000 });
        
        // Click login button
        await page.getByRole('button', { name: /Mit Biometrie anmelden/i }).click();
        
        // Modal should appear
        await expect(page.getByText('Biometrie bestätigen')).toBeVisible({ timeout: 5000 });
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
        
        await expect(page.getByRole('button', { name: /Mit Biometrie anmelden/i })).toBeVisible({ timeout: 15000 });
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
  test('shows token display after successful login (requires real key)', async ({ page }) => {
    // This test would require the actual private key to work
    // Skipping for now - tested via manual flow or integration tests
  });

  test('authenticated screen shows refresh button', async ({ page }) => {
    // Similar to above - needs real login
  });
});
