import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const dockerfile = readFileSync(new URL('../deploy/local/Dockerfile', import.meta.url), 'utf8');
const compose = readFileSync(new URL('../../server/deploy/local/compose.yml', import.meta.url), 'utf8');

test('local frontend container keeps request encryption enabled', () => {
  assert.match(dockerfile, /^ARG VITE_APP_ENCRYPT=true$/m);
  assert.match(compose, /^\s+VITE_APP_ENCRYPT: "true"$/m);
});
