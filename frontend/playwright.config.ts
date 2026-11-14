import { defineConfig } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const fixture = JSON.parse(
  readFileSync(resolve(__dirname, '../.secrets/e2e/configuration.json'), 'utf8'),
);
if (fixture.purpose !== 'synthetic-phase1-e2e')
  throw new Error('Expected synthetic E2E configuration');

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  retries: 0,
  use: { baseURL: fixture.baseURL, trace: 'off', video: 'off' },
  reporter: 'list',
});
