import assert from 'node:assert/strict';
import test from 'node:test';

test('calibration editor retains the demo interaction controls', async () => {
  const { CalibrationManager } = await import('../src/pages/store/calibration/editor/manager.mjs');
  const editor = new CalibrationManager({}, { render() {}, flash() {} });
  editor.categories = [{ id: 1, name: '地毯' }];
  const html = editor.view();
  for (const label of [
    '框选区域', '四角拉伸', '网格弯曲', '整体移动', '撤销',
    '预览缩放', '精细蒙版 PNG', '控制点精确坐标',
    '复制当前区域', '验收并发布',
  ]) assert.ok(html.includes(label), `缺少演示校准功能：${label}`);
  for (const label of ['保存草稿', '＋ 新背景', '编辑已有校准', '参数导入 / 导出', '背景管理']) {
    assert.ok(!html.includes(label), `应移除入口：${label}`);
  }
});

test('uploading another background after publishing starts a new record even at the same size', async () => {
  const { CalibrationManager } = await import('../src/pages/store/calibration/editor/manager.mjs');
  const editor = new CalibrationManager({}, { render() {}, flash() {} });
  editor.id = 'published-id';
  editor.background = new File(['old'], 'old.png', { type: 'image/png' });
  editor.config.width = 100;
  editor.config.height = 100;
  editor.decode = async () => ({ naturalWidth: 100, naturalHeight: 100 });
  const next = new File(['new'], 'new.png', { type: 'image/png' });
  await editor.change({ id: 'cal-background', files: [next] });
  assert.equal(editor.id, null);
  assert.equal(editor.background, next);
  assert.equal(editor.name, 'new');
  assert.ok(editor.history.every(checkpoint => checkpoint.id == null));
});

test('publishing uses the provided application confirmation', async () => {
  const { CalibrationManager } = await import('../src/pages/store/calibration/editor/manager.mjs');
  let asked = 0;
  const editor = new CalibrationManager({ saveCalibration() { throw Error('cancelled publish must not save'); } }, {
    render() {}, flash() {}, confirmPublish: async () => { asked++; return false; },
  });
  editor.background = new File(['background'], 'background.png', { type: 'image/png' });
  await editor.handle('cal:publish');
  assert.equal(asked, 1);
});

test('publishing clears the workspace after confirmation', async () => {
  const { CalibrationManager } = await import('../src/pages/store/calibration/editor/manager.mjs');
  const editor = new CalibrationManager({
    async saveCalibration() { return { id: 'published-id' }; },
  }, { render() {}, flash() {}, confirmPublish: async () => true });
  editor.background = new File(['background'], 'background.png', { type: 'image/png' });
  await editor.handle('cal:publish');
  assert.equal(editor.id, null);
  assert.equal(editor.background, null);
  assert.equal(editor.dirty, false);
});

test('empty workspace upload shortcut opens the assets panel', async () => {
  const { CalibrationManager } = await import('../src/pages/store/calibration/editor/manager.mjs');
  const editor = new CalibrationManager({}, { render() {}, flash() {} });
  editor.inspectorTab = 'shape';
  await editor.handle('cal:panel', 'assets');
  assert.equal(editor.inspectorTab, 'assets');
});
