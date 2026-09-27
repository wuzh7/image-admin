import { Worker } from 'node:worker_threads';

export function renderParallel(data) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./render-thread.mjs', import.meta.url), { workerData: data });
    worker.once('message', message => message.error ? reject(new Error(message.error)) : resolve(Buffer.from(message.result)));
    worker.once('error', reject);
    worker.once('exit', code => { if (code !== 0) reject(new Error(`渲染线程退出: ${code}`)); });
  });
}
