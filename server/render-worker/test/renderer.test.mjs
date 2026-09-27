import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createCanvas, loadImage } from '@napi-rs/canvas';
import { renderOne, resolveTemplate } from '../src/renderer.mjs';
import { renderParallel } from '../src/render-parallel.mjs';
import { defaultCalibration, multiCalibration } from '../../../web/src/pages/store/calibration/engine/schema.mjs';

function solid(width, height, color) {
  const canvas = createCanvas(width, height);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = color;
  ctx.fillRect(0, 0, width, height);
  return canvas.toBuffer('image/png');
}

test('renders a real generated scene with the Node Canvas runtime', async () => {
  const output = await renderOne({
    item: { itemId: 1, templateConfigJson: JSON.stringify({ sceneId: '002' }), outputFormat: 'PNG', quality: 0.9 },
    design: solid(120, 120, '#dc5262'),
    background: solid(800, 800, '#f2eee6'),
  });
  assert.deepEqual([...output.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]);
  assert.ok(output.length > 1000);
  assert.equal(resolveTemplate({ templateConfigJson: JSON.stringify({ sceneId: '002' }) }).sceneId, '002');
});

test('renders five scenes in separate threads', async () => {
  const data = {
    item: { itemId: 1, templateConfigJson: JSON.stringify({ sceneId: '002' }), outputFormat: 'PNG', quality: 0.9 },
    design: solid(120, 120, '#dc5262'), background: solid(800, 800, '#f2eee6'),
  };
  const outputs = await Promise.all(Array.from({ length: 5 }, () => renderParallel(data)));
  outputs.forEach(output => assert.deepEqual([...output.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]));
});

test('accepts a complete declaration from the calibration payload', () => {
  const declaration = { engine: 'declarative-canvas-v1', schemaVersion: 1 };
  assert.deepEqual(resolveTemplate({ calibrationConfigJson: JSON.stringify(declaration), templateConfigJson: '{}' }).declaration, declaration);
});

test('uses each template declaration instead of the group default', () => {
  const group = { engine: 'declarative-canvas-v1', width: 100 };
  const selected = { engine: 'declarative-canvas-v1', width: 200 };
  assert.deepEqual(resolveTemplate({ calibrationConfigJson: JSON.stringify(group),
    templateConfigJson: JSON.stringify({ declaration: selected }) }).declaration, selected);
});

test('renders a published multi-region calibration from the editor', async () => {
  const calibration = multiCalibration([{
    id: 'region-1', name: '区域 1', enabled: true, config: defaultCalibration(120, 120),
  }]);
  const output = await renderOne({
    item: { itemId: 2, calibrationConfigJson: JSON.stringify(calibration), templateConfigJson: '{}', outputFormat: 'PNG', quality: 90 },
    design: solid(80, 80, '#dc5262'), background: solid(120, 120, '#f2eee6'),
  });
  assert.deepEqual([...output.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]);
});

test('applies a published PNG mask in the Node render', async () => {
  const config = defaultCalibration(120, 120);
  config.watermark.opacity = 0;
  const item = { itemId: 3, calibrationConfigJson: JSON.stringify(multiCalibration([
    { id: 'region-1', name: '区域 1', enabled: true, config },
  ])), templateConfigJson: '{}', outputFormat: 'PNG', quality: 90 };
  const background = solid(120, 120, '#eeeeee');
  const unmasked = await renderOne({ item, design: solid(80, 80, '#ff0000'), background });
  const masked = await renderOne({ item, design: solid(80, 80, '#ff0000'), background, mask: solid(120, 120, '#000000') });
  const sample = async (buffer) => {
    const canvas = createCanvas(120, 120);
    canvas.getContext('2d').drawImage(await loadImage(buffer), 0, 0);
    return [...canvas.getContext('2d').getImageData(60, 70, 1, 1).data];
  };
  assert.notDeepEqual(await sample(unmasked), await sample(masked));
  assert.deepEqual(await sample(masked), [238, 238, 238, 255]);
});
