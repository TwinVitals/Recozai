import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const fixture = JSON.parse(
  readFileSync(resolve(__dirname, '../../.secrets/e2e/configuration.json'), 'utf8'),
);
const token = readFileSync(resolve(__dirname, '../../.secrets/e2e/challenge.txt'), 'utf8');

test.beforeAll(async ({ request }) => {
  const csrf = await (await request.get('/api/v1/session/csrf')).json();
  const response = await request.post('/api/v1/account-activations', {
    headers: { 'X-XSRF-TOKEN': csrf.token },
    data: { token, password: fixture.password },
  });
  expect(response.status()).toBe(204);
});

test('real session, dirty-form localization, settings, invitations, audit and logout', async ({
  page,
}) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await page.goto('/login');
  await page.getByLabel('Identifiant', { exact: true }).fill(fixture.login);
  await page.getByLabel('Mot de passe', { exact: true }).fill(fixture.password);
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Un socle contrôlé pour votre clinique' }),
  ).toBeVisible();
  await page.getByRole('link', { name: 'Paramètres de la clinique' }).click();
  await page.getByLabel('Nom de la clinique').fill('Synthetic revised clinic');
  await page.getByLabel('Langue', { exact: true }).first().selectOption('en');
  await expect(page.getByLabel('Clinic name')).toHaveValue('Synthetic revised clinic');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.getByText('Saved', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Clinic name')).toHaveValue('Synthetic revised clinic');
  await page.getByRole('link', { name: 'Payers and rules' }).click();
  await expect(page.getByRole('heading', { name: 'Payers and rules' })).toBeVisible();
  await expect(
    page.getByText(
      'You have read-only access to payer and category configuration. Only a billing manager can create or modify payers, categories or draft rules.',
    ),
  ).toBeVisible();
  await page.getByRole('link', { name: 'Clinic memberships' }).click();
  await page.getByLabel('Login', { exact: true }).fill('synthetic-invitee');
  await page.getByRole('button', { name: 'Invite to this clinic' }).click();
  await expect(page.getByLabel('Invitation ID')).not.toHaveValue('');
  await page.getByRole('link', { name: 'Administration audit' }).click();
  await expect(page.getByRole('cell', { name: 'Membership invited' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome Back', exact: true })).toBeVisible();
  await page.goto('/settings');
  await expect(page).toHaveURL(/\/login$/);
  expect(errors.filter((error) => !error.includes('401'))).toEqual([]);
});
