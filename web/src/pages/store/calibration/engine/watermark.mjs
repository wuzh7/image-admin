// 模板坐标中的独立水印图层，不读取或改写上传图片。
// 仅缓存一个小纹理块；各模板 renderFrame 在自己的覆盖区域内合成。
// R14：浅灰可见水印，细灰轮廓辅助浅色/近灰图案辨识，不保证不可移除。
let cachedWatermark;
export function getTemplateWatermark(ctx, width, height) {
  const unit = Math.max(width, height) / 1100;
  const size = Math.max(32, Math.round(160 * unit));
  if (cachedWatermark?.size === size) return cachedWatermark;
  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.globalAlpha = 1;
  ctx.globalCompositeOperation = 'source-over';
  ctx.clearRect(0, 0, size, size);
  ctx.translate(size / 2, size / 2);
  ctx.rotate(-Math.PI / 7);
  ctx.font = `500 ${18 * unit}px sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.strokeStyle = 'rgba(144,144,144,0.14)';
  ctx.lineWidth = 0.45 * unit;
  ctx.strokeText('WATERMARK', 0, 0);
  ctx.fillStyle = 'rgba(184,184,184,0.26)';
  ctx.fillText('WATERMARK', 0, 0);
  ctx.restore();
  cachedWatermark = { size, data: ctx.getImageData(0, 0, size, size).data };
  ctx.clearRect(0, 0, size, size);
  return cachedWatermark;
}

export function blendTemplateWatermark(value, layer, x, y) {
  const i = ((y % layer.size) * layer.size + (x % layer.size)) * 4;
  const delta = ((layer.data[i] - value) * layer.data[i + 3]) / 255;
  // 深色图限制提亮幅度，避免浅灰文字变得刺眼；浅色图仍可辨识。
  return value + Math.max(-32, Math.min(32, delta));
}
