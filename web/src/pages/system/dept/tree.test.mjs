import assert from 'node:assert/strict';
import test from 'node:test';
import { buildDeptTree, disableCurrentDept, filterDeptTree, togglePermissionBranch } from './tree.ts';

test('部门列表只为真正的父部门生成可展开行，搜索保留祖先路径', () => {
  const rows = [
    { deptId: '100', parentId: 0, deptName: '总测试', status: '0', children: [] },
    { deptId: '101', parentId: '100', deptName: '测试1', status: '0', children: [] },
    { deptId: '102', parentId: 0, deptName: '运营1', status: '0', children: [] }
  ];
  const tree = buildDeptTree(rows);
  assert.deepEqual(tree.map(row => row.deptName), ['总测试', '运营1']);
  assert.deepEqual(tree[0].children?.map(row => row.deptName), ['测试1']);
  assert.equal('children' in tree[0].children[0], false);
  assert.equal('children' in tree[1], false);
  const filtered = filterDeptTree(tree, '测试1');
  assert.deepEqual(filtered.map(row => row.deptName), ['总测试']);
  assert.deepEqual(filtered[0].children?.map(row => row.deptName), ['测试1']);
});

test('修改部门时不能把自己或下级选为上级', () => {
  const options = disableCurrentDept([{ id: '100', label: '总测试', children: [{ id: '101', label: '测试1' }] }], 100);
  assert.equal(options[0].disabled, true);
  assert.equal(options[0].children[0].disabled, true);
});

test('勾选父目录同时勾选子菜单和按钮，取消时只清除该分支', () => {
  const nodes = [
    { id: 'root', label: '目录', children: [{ id: 'page', label: '菜单', children: [{ id: 'button', label: '按钮' }] }] },
    { id: 'other', label: '其他' }
  ];
  assert.deepEqual(togglePermissionBranch(nodes, ['other'], 'root', true), ['other', 'root', 'page', 'button']);
  assert.deepEqual(togglePermissionBranch(nodes, ['other', 'root', 'page', 'button'], 'root', false), ['other']);
  assert.deepEqual(togglePermissionBranch(nodes, ['root'], 'other', true), ['root', 'other']);
});
