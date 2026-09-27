import assert from 'node:assert/strict';
import test from 'node:test';
import { createLatestRequestGate } from '../src/pages/image/dedup/requestGate.ts';

test('an older task drawer response cannot overwrite the latest selected batch', () => {
  const gate = createLatestRequestGate();
  const firstBatch = gate.begin('task-drawer', 'batch-1');
  const secondBatch = gate.begin('task-drawer', 'batch-2');

  assert.equal(gate.accepts(firstBatch), false);
  assert.equal(gate.accepts(secondBatch), true);
});

test('review page requests for separate batches do not invalidate each other', () => {
  const gate = createLatestRequestGate();
  const firstBatch = gate.begin('review-batch-1', 'page-1');
  const secondBatch = gate.begin('review-batch-2', 'page-1');

  assert.equal(gate.accepts(firstBatch), true);
  assert.equal(gate.accepts(secondBatch), true);
});

test('closing a drawer invalidates its pending response', () => {
  const gate = createLatestRequestGate();
  const pending = gate.begin('task-drawer', 'batch-1');
  gate.invalidate('task-drawer');

  assert.equal(gate.accepts(pending), false);
});
