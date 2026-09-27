import { createHash } from 'node:crypto';
import { loadImage } from '@napi-rs/canvas';
import { hostname } from 'node:os';
import { setTimeout as sleep } from 'node:timers/promises';
import { JavaClient, resultOssKey } from './client.mjs';
import { renderParallel } from './render-parallel.mjs';

const env = process.env;
const baseUrl = env.JAVA_API_URL ?? 'http://backend:8080';
const token = env.RENDER_WORKER_TOKEN;
if (!token) throw new Error('RENDER_WORKER_TOKEN is required');
const workerId = env.RENDER_WORKER_ID ?? `${hostname()}-${process.pid}`;
const concurrency = Math.max(1, Number(env.RENDER_WORKER_CONCURRENCY ?? 1));
const pollMs = Math.max(250, Number(env.RENDER_WORKER_POLL_MS ?? 2000));
const client = new JavaClient({ baseUrl, token, timeoutMs: Number(env.RENDER_WORKER_HTTP_TIMEOUT_MS ?? 60000) });
let stopping = false;
const running = new Set();

function assetUrl(value) {
  if (!value) throw new Error('Java 未返回图片 URL');
  return new URL(value, `${baseUrl}/`).toString();
}

async function fetchAsset(value) {
  const resolved = String(value).startsWith('/store/internal/')
    ? await client.request(value, { method: 'GET' })
    : value;
  const url = assetUrl(resolved);
  const headers = new URL(url).origin === new URL(baseUrl).origin
    ? { 'X-Render-Worker-Token': token }
    : undefined;
  const response = await fetch(url, {
    ...(headers ? { headers } : {}),
    signal: AbortSignal.timeout(Number(env.RENDER_WORKER_ASSET_TIMEOUT_MS ?? 120000)),
  });
  if (!response.ok) throw new Error(`读取图片失败 ${response.status}`);
  return Buffer.from(await response.arrayBuffer());
}

async function processItem(raw) {
  const item = { ...raw, workerId };
  const heartbeat = setInterval(() => {
    void client.renew(item).catch(error => console.error(JSON.stringify({ event: 'lease_renew_failed', workerId, itemId: item.itemId, error: String(error) })));
  }, 60_000);
  try {
    const [design, background, mask] = await Promise.all([
      fetchAsset(item.imageUrl ?? item.imageAccessUrl),
      fetchAsset(item.templateUrl ?? item.templateAccessUrl),
      item.maskUrl ? fetchAsset(item.maskUrl) : Promise.resolve(undefined),
    ]);
    const result = await renderParallel({ item, design, background, mask });
    const format = String(item.outputFormat ?? 'PNG').toLowerCase() === 'jpg' ? 'jpeg' : String(item.outputFormat ?? 'PNG').toLowerCase();
    const dimensions = await loadImage(result);
    const uploaded = await client.upload(item, result, format);
    const ossKey = resultOssKey(uploaded);
    if (!ossKey) throw new Error('Java 上传接口未返回 ossKey');
    await client.complete(item, {
      ossKey,
      sha256: createHash('sha256').update(result).digest('hex'),
      width: dimensions.width,
      height: dimensions.height,
    });
    console.log(JSON.stringify({ event: 'render_succeeded', workerId, itemId: item.itemId }));
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(JSON.stringify({ event: 'render_failed', workerId, itemId: item.itemId, error: message }));
    try {
      await client.fail(item, message);
    } catch (reportError) {
      console.error(JSON.stringify({ event: 'fail_report_failed', workerId, itemId: item.itemId, error: String(reportError) }));
    }
  } finally {
    clearInterval(heartbeat);
  }
}

async function drain(items) {
  for (const item of items) {
    while (running.size >= concurrency) await Promise.race(running);
    const task = processItem(item).finally(() => running.delete(task));
    running.add(task);
  }
}

async function main() {
  console.log(JSON.stringify({ event: 'worker_started', workerId, baseUrl, concurrency }));
  while (!stopping) {
    try {
      if (running.size >= concurrency) {
        await Promise.race(running);
        continue;
      }
      const claimed = await client.claim(workerId, concurrency - running.size);
      const items = Array.isArray(claimed) ? claimed : claimed?.items ?? [];
      if (items.length) await drain(items);
      else await sleep(Math.max(pollMs, Number(claimed?.nextPollMs ?? 0)));
    } catch (error) {
      console.error(JSON.stringify({ event: 'poll_failed', workerId, error: String(error) }));
      await sleep(Math.min(30000, pollMs * 2));
    }
  }
  await Promise.allSettled([...running]);
  console.log(JSON.stringify({ event: 'worker_stopped', workerId }));
}

process.once('SIGTERM', () => { stopping = true; });
process.once('SIGINT', () => { stopping = true; });

if (import.meta.url === `file://${process.argv[1]}`) main().catch((error) => { console.error(error); process.exitCode = 1; });

export { processItem, fetchAsset };
