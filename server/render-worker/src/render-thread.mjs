import { parentPort, workerData } from 'node:worker_threads';
import { renderOne } from './renderer.mjs';

try {
  const result = await renderOne(workerData);
  parentPort.postMessage({ result });
} catch (error) {
  parentPort.postMessage({ error: error instanceof Error ? error.message : String(error) });
}
