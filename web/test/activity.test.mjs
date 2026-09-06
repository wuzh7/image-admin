import assert from 'node:assert/strict';
import test from 'node:test';
import { formatActivityTime, formatOperationActivity } from '../src/utils/activity.ts';

test('formats an image upload as a user-facing activity', () => {
  const activity = formatOperationActivity({
    operId: '1',
    title: '图片批次上传',
    businessType: 1,
    operName: '李小北',
    operParam: '"2094780511001985026"',
    jsonResult: '{"code":200,"data":10,"msg":"操作成功"}',
    status: 0,
    operTime: '2026-09-04 09:00:00'
  });

  assert.equal(activity.title, '李小北上传了 10 张图片');
  assert.equal(activity.summary, '批次 2094780511001985026 · 上传完成');
});

test('formats a completed manual review with its outcome', () => {
  const activity = formatOperationActivity({
    operId: '2',
    title: '图片人工确认',
    businessType: 2,
    operName: '陈晨',
    operParam: '"2094748151124639745" {"approvedIds":["1","2"]}',
    status: 0,
    operTime: '2026-09-04 09:00:00'
  });

  assert.equal(activity.title, '陈晨完成了批次 2094748151124639745 的人工审核');
  assert.equal(activity.summary, '2 张图片已进入正式图库');
});

test('uses a business verb for generic administration activity', () => {
  const activity = formatOperationActivity({
    operId: '3',
    title: '角色管理',
    businessType: 1,
    operName: '管理员',
    status: 0,
    operTime: '2026-09-04 09:00:00'
  });

  assert.equal(activity.title, '管理员新增了角色');
  assert.equal(activity.summary, '操作成功');
});

test('does not repeat the action suffix in order detail activity titles', () => {
  const cases = [
    ['订单明细新增', 1, '管理员新增了订单明细'],
    ['订单明细修改', 2, '管理员修改了订单明细'],
    ['订单明细导出', 5, '管理员导出了订单明细']
  ];

  for (const [title, businessType, expected] of cases) {
    const activity = formatOperationActivity({
      operId: '4',
      title,
      businessType,
      operName: '管理员',
      status: 0,
      operTime: '2026-09-04 09:00:00'
    });

    assert.equal(activity.title, expected);
  }
});

test('formats activity time in plain language', () => {
  const now = new Date('2026-09-04T09:31:00');
  assert.equal(formatActivityTime('2026-09-04 09:00:00', now), '31分钟前');
  assert.equal(formatActivityTime('2026-09-04 09:30:45', now), '刚刚');
});
