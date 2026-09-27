import { createCanvas, loadImage } from '@napi-rs/canvas';
import workerSource from '../runtime/worker-source.mjs';

let runtimeFactory;


function closeable(value) {
  if (value && typeof value.close !== 'function') value.close = () => {};
  return value;
}

function makeCanvas(width, height) {
  const canvas = createCanvas(width, height);
  canvas.convertToBlob = async ({ type = 'image/png', quality = 0.9 } = {}) => {
    const options = type === 'image/jpeg' ? { quality: Math.round(quality * 100) } : undefined;
    return new Blob([canvas.toBuffer(type, options)], { type });
  };
  return closeable(canvas);
}

async function createImageBitmap(value) {
  if (value && typeof value.getContext === 'function') return closeable(value);
  if (value instanceof Blob) return closeable(await loadImage(Buffer.from(await value.arrayBuffer())));
  if (value instanceof ArrayBuffer || ArrayBuffer.isView(value))
    return closeable(await loadImage(Buffer.from(value.buffer ?? value, value.byteOffset ?? 0, value.byteLength)));
  throw new TypeError('renderer expects a Buffer, Blob, or canvas image');
}

function getRuntimeFactory() {
  return runtimeFactory ??= Function('self', 'OffscreenCanvas', 'createImageBitmap', `${workerSource}\nreturn self.onmessage;`);
}

function parseJson(value, field) {
  if (value == null || value === '') return undefined;
  if (typeof value === 'object') return value;
  try {
    return JSON.parse(value);
  } catch (error) {
    throw new Error(`${field} 不是有效 JSON: ${error.message}`);
  }
}

export function resolveTemplate(item) {
  const template = parseJson(item.templateConfigJson, 'templateConfigJson') ?? {};
  const calibration = parseJson(item.calibrationConfigJson, 'calibrationConfigJson');
  const declaration = template.declaration ?? (template.engine ? template : calibration?.engine ? calibration : undefined);
  const sceneId = template.sceneId ?? template.scene ?? (typeof template.id === 'string' ? template.id : undefined);
  if (!declaration && !sceneId) throw new Error(`明细 ${item.itemId} 缺少 sceneId 或校准配置`);
  return { sceneId, declaration };
}

export async function renderOne({ item, design, background, mask }) {
  const { sceneId, declaration } = resolveTemplate(item);
  const format = String(item.outputFormat ?? 'PNG').toLowerCase();
  const rawQuality = Number(item.quality ?? 0.9);
  const output = { format: format === 'jpg' ? 'jpeg' : format, quality: rawQuality > 1 ? rawQuality / 100 : rawQuality };
  if (!['png', 'jpeg'].includes(output.format)) throw new Error(`不支持的输出格式: ${format}`);
  if (!Number.isFinite(output.quality) || output.quality < 0.6 || output.quality > 1)
    throw new Error('输出质量必须在 0.6 到 1 之间');
  const self = { postMessage(value) { self.message = value; } };
  const run = getRuntimeFactory()(
    self,
    class OffscreenCanvas {
      constructor(width, height) {
        return makeCanvas(width, height);
      }
    },
    createImageBitmap,
  );
  await run({ data: { design, background, mask, output, ...(declaration ? { declaration } : { sceneId: String(sceneId).padStart(3, '0') }) } });
  if (self.message?.error) throw new Error(self.message.error);
  if (!(self.message?.blob instanceof Blob)) throw new Error('渲染器没有返回图片');
  return Buffer.from(await self.message.blob.arrayBuffer());
}
