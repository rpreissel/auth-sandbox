import { test, expect } from '@playwright/test';

test.describe('admin-mock-react', () => {
  test('shows login overlay initially', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#login-overlay')).toBeVisible();
  });

  test('shows login form with inputs', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('Admin Login')).toBeVisible();
    await expect(page.getByLabel('Username')).toBeVisible();
    await expect(page.getByLabel('Password')).toBeVisible();
  });

  test('login button shows error with empty password', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByText('Username and password are required.')).toBeVisible();
  });

  test('prefilled username is admin', async ({ page }) => {
    await page.goto('/');
    const usernameInput = page.getByLabel('Username');
    await expect(usernameInput).toHaveValue('admin');
  });

  test('shows error with empty credentials', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Login' }).click();
    await expect(page.getByText('Username and password are required.')).toBeVisible();
  });
});
