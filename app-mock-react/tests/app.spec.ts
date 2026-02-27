import { test, expect } from '@playwright/test';

test.describe('app-mock-react', () => {
  test('shows unregistered screen after loading', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('Gerät registrieren')).toBeVisible({ timeout: 10000 });
  });

  test('shows header with app name', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('Device Auth Mock')).toBeVisible();
  });

  test('shows status pill for unregistered state', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('Nicht registriert')).toBeVisible();
  });

  test('shows registration form inputs', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByPlaceholder('User-ID (vom Admin)')).toBeVisible();
    await expect(page.getByPlaceholder('Dein Name')).toBeVisible();
    await expect(page.getByPlaceholder('Freischaltcode')).toBeVisible();
  });

  test('shows settings toggle', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('Erweiterte Einstellungen')).toBeVisible();
  });

  test('shows activity log', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('Aktivitätslog')).toBeVisible();
  });

  test('registration button is disabled with empty form', async ({ page }) => {
    await page.goto('/');
    const button = page.getByRole('button', { name: /Gerät einrichten/i });
    await expect(button).toBeDisabled();
  });

  test('can expand settings', async ({ page }) => {
    await page.goto('/');
    await page.getByText('Erweiterte Einstellungen').click();
    await expect(page.getByPlaceholder('Backend URL')).toBeVisible();
  });
});
