import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const orderSql = readFileSync(new URL('../../server/script/sql/order.sql', import.meta.url), 'utf8');

test('places order details in the top-level sidebar', () => {
  assert.match(
    orderSql,
    /\(1990000000000000010, '订单明细', 0, 3, 'order-details', 'order\/detail\/index'/
  );
});
