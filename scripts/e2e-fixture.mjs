import { generateKeyPairSync, randomBytes, randomUUID, createPrivateKey, sign } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const directory = resolve(import.meta.dirname, '../.secrets/e2e');
const configuration = resolve(directory, 'configuration.json');
const mode = process.argv[2];
if (mode === 'prepare') {
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const { publicKey, privateKey } = generateKeyPairSync('ed25519');
  const epoch = randomUUID();
  const config = {
    purpose: 'synthetic-phase1-e2e', baseURL: 'http://127.0.0.1:18080',
    login: 'synthetic-admin', password: randomBytes(24).toString('base64url'), epoch,
    privateKey: privateKey.export({ type: 'pkcs8', format: 'pem' })
  };
  writeFileSync(configuration, JSON.stringify(config), { flag: 'wx', mode: 0o600 });
  const publicDer = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  writeFileSync(resolve(directory, 'compose.env'), [
    `POSTGRES_PASSWORD=${randomBytes(32).toString('hex')}`, `SECURITY_EPOCH=${epoch}`,
    'RESTORE_GATE=OPEN', 'HTTP_PORT=18080', `CUSTODIAN_PUBLIC_KEY=${publicDer}`, ''
  ].join('\n'), { flag: 'wx', mode: 0o600 });
} else if (mode === 'grant') {
  const config = JSON.parse(readFileSync(configuration, 'utf8'));
  if (config.purpose !== 'synthetic-phase1-e2e') throw new Error('Not a synthetic fixture');
  const payload = Buffer.from(JSON.stringify({
    nonce: randomUUID(), action: 'BOOTSTRAP', login: config.login, clinicName: 'Synthetic clinic',
    custodian: 'synthetic-test-custodian', verificationReference: 'synthetic-test-owner',
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(), securityEpoch: config.epoch
  }));
  const signature = sign(null, payload, createPrivateKey(config.privateKey));
  writeFileSync(resolve(directory, 'grant.txt'), `${payload.toString('base64url')}.${signature.toString('base64url')}`, { flag: 'wx', mode: 0o600 });
} else {
  throw new Error('Use prepare or grant; fixtures are created exclusively, never overwritten');
}
