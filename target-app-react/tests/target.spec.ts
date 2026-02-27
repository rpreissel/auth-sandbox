import { test, expect } from '@playwright/test';

test.describe('target-app-react', () => {
  test('redirects to Keycloak on load', async ({ page }) => {
    await page.goto('http://localhost:5175');
    await page.waitForURL(/keycloak.localhost/, { timeout: 15000 });
  });
});
