import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const orderSql = readFileSync(new URL('../../server/script/sql/order.sql', import.meta.url), 'utf8');
const dynamicPage = readFileSync(new URL('../src/pages/dynamicPage.tsx', import.meta.url), 'utf8');
const orderPage = readFileSync(new URL('../src/pages/order/detail/index.tsx', import.meta.url), 'utf8');
const orderTypes = readFileSync(new URL('../src/api/order/types.ts', import.meta.url), 'utf8');
const orderDtos = readFileSync(
  new URL('../../server/wuzh-modules/wuzh-order/src/main/java/org/dromara/order/domain/OrderDetailDtos.java', import.meta.url),
  'utf8'
);
const orderService = readFileSync(
  new URL('../../server/wuzh-modules/wuzh-order/src/main/java/org/dromara/order/service/OrderDetailService.java', import.meta.url),
  'utf8'
);
const orderQuery = orderTypes.slice(
  orderTypes.indexOf('export interface OrderDetailQuery'),
  orderTypes.indexOf('export interface OrderDetailForm')
);
const orderDtoQuery = orderDtos.slice(
  orderDtos.indexOf('public static class Query'),
  orderDtos.indexOf('public static class SaveRequest')
);

test('places order details in the top-level sidebar', () => {
  assert.match(
    orderSql,
    /\(1990000000000000010, '订单明细', 0, 3, 'order-details', 'order\/detail\/index'/
  );
});

test('keeps the order detail page visible only to super administrators', () => {
  assert.match(orderSql, /role\.role_key <> 'superadmin'/);
  assert.match(orderSql, /WHERE role\.role_key = 'superadmin'/);
  assert.match(dynamicPage, /componentKey === 'order\/detail\/index'/);
});

test('uses customized-product and store-remark filters instead of child order number', () => {
  assert.match(orderPage, /title: '子订单号', dataIndex: 'orderSn',[^\n]*search: false/);
  assert.match(orderPage, /title: '店铺备注', dataIndex: 'storeRemark', width: 180, ellipsis: true/);
  assert.match(orderPage, /title: '是否定制', dataIndex: 'customizedProducts', width: 120, ellipsis: true/);
  assert.match(orderQuery, /customizedProducts\?: string/);
  assert.match(orderDtoQuery, /private String storeRemark;/);
  assert.match(orderDtoQuery, /private String customizedProducts;/);
  assert.match(orderService, /"customized_products"/);
  assert.match(orderService, /getStoreRemark\(\)/);
});
