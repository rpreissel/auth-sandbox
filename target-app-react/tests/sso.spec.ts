import { test, expect } from '@playwright/test';

test.describe('target-app-react SSO', () => {
  test('redirects to Keycloak when no session exists', async ({ page }) => {
    await page.goto('http://localhost:5175');
    
    // Should redirect to Keycloak
    await page.waitForURL(/keycloak\.localhost/, { timeout: 15000 });
  });

  test('shows appropriate state during OIDC flow', async ({ page }) => {
    await page.goto('http://localhost:5175');
    
    // Should show redirecting status initially
    await expect(page.getByText(/Redirecting to Keycloak/i)).toBeVisible({ timeout: 15000 });
  });
});
