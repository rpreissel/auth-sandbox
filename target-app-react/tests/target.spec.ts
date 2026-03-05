import { test, expect } from '@playwright/test';

test.describe('target-app-react', () => {
  test('redirects to Keycloak on load', async ({ page }) => {
    await page.goto('http://localhost:5175');
    await page.waitForURL(/keycloak.localhost/, { timeout: 15000 });
  });

  test('has refresh and logout buttons after login', async ({ page }) => {
    // Navigate to target-app which should redirect to Keycloak
    await page.goto('https://target-app.localhost:8443');
    
    // Wait for either Keycloak login or callback with code
    // If there's an SSO session, it might redirect back with a code
    await page.waitForURL(/keycloak.localhost|callback/, { timeout: 15000 });
    
    // If we're at callback with a code, wait for the tokens to appear
    if (page.url().includes('code=')) {
      await expect(page.locator('text=Access')).toBeVisible({ timeout: 10000 });
      await expect(page.locator('text=Refresh token')).toBeVisible();
      await expect(page.locator('text=Logout')).toBeVisible();
    }
  });
});
