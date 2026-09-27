import assert from 'node:assert/strict';
import { test } from 'node:test';
import { JavaClient, resultOssKey } from '../src/client.mjs';

test('uses the worker contract and unwraps the Java R envelope', async () => {
  const calls = [];
  const client = new JavaClient({
    baseUrl: 'http://backend:8080',
    token: 'secret',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return new Response(JSON.stringify({ code: 200, data: { ossKey: 'result/key.jpg' } }), { status: 200, headers: { 'content-type': 'application/json' } });
    },
  });
  const item = { itemId: 7, workerId: 'worker-1', leaseToken: 'lease-1' };
  assert.equal(resultOssKey(await client.upload(item, Buffer.from('jpg'), 'jpeg')), 'result/key.jpg');
  await client.complete(item, { ossKey: 'result/key.jpg', sha256: 'hash', width: 800, height: 800 });
  await client.fail(item, 'bad render');
  assert.equal(calls[0].url, 'http://backend:8080/store/internal/render-items/7/upload');
  assert.equal(calls[1].url, 'http://backend:8080/store/internal/render-items/7/complete');
  assert.equal(calls[2].url, 'http://backend:8080/store/internal/render-items/7/fail');
  assert.equal(calls[1].options.headers['X-Render-Worker-Token'], 'secret');
});
