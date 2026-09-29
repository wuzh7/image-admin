import { validateCalibration, regionCalibration } from './schema.mjs';
import { roundedContour } from './geometry.mjs';
import { curvedMesh } from './curvature.mjs';
import { whiteCoverage } from './white-mask.mjs';
import { blackCoverage } from './black-mask.mjs';
import { greenCoverage } from './green-mask.mjs';
import { extractFabric, fabricAt, luma } from './fabric.mjs';
import { surfaceLight } from './surface.mjs';
import { visibleSideBounds, visibleSideLight } from './side-bands.mjs';
import { refineBlackEdges, decontaminateEdges } from './edge-refine.mjs';

const make = (w, h) =>
  typeof OffscreenCanvas !== 'undefined'
    ? new OffscreenCanvas(w, h)
    : Object.assign(document.createElement('canvas'), { width: w, height: h });

function regionMatte(base, c, mask, W, H, scale) {
  let repairs = [];
  let edgeWarning = '';
  const matte = make(W, H),
    mc = matte.getContext('2d', { willReadFrequently: true });
  mc.fillStyle = '#fff';
  mc.beginPath();
  const border = [],
    columns = c.gridSize,
    rows = c.gridSize;
  for (let x = 0; x < columns; x++) border.push(c.points[x]);
  for (let y = 1; y < rows; y++) border.push(c.points[y * columns + columns - 1]);
  for (let x = columns - 2; x >= 0; x--) border.push(c.points[(rows - 1) * columns + x]);
  for (let y = rows - 2; y > 0; y--) border.push(c.points[y * columns]);
  (c.cornerRadius ? roundedContour(c) : border).forEach((p, i) =>
    i ? mc.lineTo(p[0] * W, p[1] * H) : mc.moveTo(p[0] * W, p[1] * H),
  );
  mc.closePath();
  mc.fill();
  mc.globalCompositeOperation = 'destination-out';
  for (const polygon of c.blackMask.enabled || c.greenMask.enabled || c.whiteMask.enabled
    ? []
    : c.exclusions) {
    mc.beginPath();
    polygon.forEach((p, i) => (i ? mc.lineTo(p[0] * W, p[1] * H) : mc.moveTo(p[0] * W, p[1] * H)));
    mc.closePath();
    mc.fill();
  }
  if (c.blackMask.enabled || c.greenMask.enabled || c.whiteMask.enabled) {
    const key = c.blackMask.enabled
      ? blackCoverage
      : c.greenMask.enabled
        ? greenCoverage
        : whiteCoverage;
    const settings = c.blackMask.enabled
      ? c.blackMask
      : c.greenMask.enabled
        ? c.greenMask
        : c.whiteMask;
    const alpha = mc.getImageData(0, 0, W, H);
    const geometry =
      c.edgeRefine.enabled &&
      (c.blackMask.enabled || c.greenMask.enabled) &&
      !(c.surface.enabled && c.surface.source === 'background')
        ? new Uint8ClampedArray(alpha.data)
        : null;
    for (let i = 0; i < alpha.data.length; i += 4) {
      if (!alpha.data[i + 3]) continue;
      alpha.data[i + 3] = Math.round(
        (alpha.data[i + 3] *
          key(base.data[i], base.data[i + 1], base.data[i + 2], settings) *
          base.data[i + 3]) /
          255,
      );
    }
    if (geometry) {
      const result = refineBlackEdges(
        base,
        geometry,
        alpha.data,
        c.edgeRefine,
        scale,
        c.greenMask.enabled ? 'green' : 'black',
      );
      repairs = result.repairs;
      if (result.limited)
        edgeWarning =
          '颜色边界过于碎片化，本区域跳过边缘净化以限制内存；请缩小范围或使用精细蒙版。';
    }
    mc.putImageData(alpha, 0, 0);
  }
  if (mask) {
    const mm = make(W, H),
      mx = mm.getContext('2d', { willReadFrequently: true });
    mx.drawImage(mask, 0, 0, W, H);
    const md = mx.getImageData(0, 0, W, H);
    for (let i = 0; i < md.data.length; i += 4)
      md.data[i + 3] = Math.round(
        (((md.data[i] + md.data[i + 1] + md.data[i + 2]) / 3) * md.data[i + 3]) / 255,
      );
    mx.putImageData(md, 0, 0);
    mc.globalCompositeOperation = 'destination-in';
    mc.drawImage(mm, 0, 0);
  }
  return { matte, repairs, edgeWarning };
}

// Fixed, data-only renderer. Inverse UV sampling avoids triangle seams; PNG restrictions remain authoritative.
export function renderDeclarative(
  ctx,
  background,
  design,
  input,
  mask = null,
  { scale = 1, view = 'composite' } = {},
) {
  if (!Number.isFinite(scale) || scale <= 0 || scale > 1) throw Error('预览缩放比例无效');
  if (!['composite', 'lighting', 'coverage', 'original'].includes(view))
    throw Error('预览模式无效');
  const scene = validateCalibration(input),
    W = Math.max(1, Math.round(scene.width * scale)),
    H = Math.max(1, Math.round(scene.height * scale));
  ctx.drawImage(background, 0, 0, W, H);
  const warnings = [];
  if (view === 'original') return { warnings };
  // Every key reads the original background, never a previously painted region.
  const base = ctx.getImageData(0, 0, W, H);
  const regions =
    scene.schemaVersion === 2
      ? scene.regions.filter((r) => r.enabled).map((r) => regionCalibration(scene, r))
      : [scene];
  for (let index = 0; index < regions.length; index++) {
    const warning = renderRegion(ctx, base, design, regions[index], mask, scale, W, H, view);
    if (warning) warnings.push(`区域 ${index + 1}：${warning}`);
  }
  return { warnings };
}

function renderRegion(ctx, base, design, c, mask, scale, W, H, view) {
  c = curvedMesh(c);
  const numericSides = c.surface.edgeWidths !== undefined;
  const legacyOriginalSurface =
    !numericSides && c.surface.enabled && c.surface.source === 'background';
  const flatSurface = { shade: 1, sheen: 0 };
  const { matte, repairs, edgeWarning } = regionMatte(base, c, mask, W, H, scale);
  const mc = matte.getContext('2d'),
    mattePixels = mc.getImageData(0, 0, W, H).data,
    field =
      c.fabric.enabled && !legacyOriginalSurface
        ? extractFabric(base, mattePixels, c.fabric, scale)
        : null,
    material = { shade: 1, sheen: 0, dx: 0, dy: 0 };
  const sideBounds =
    numericSides && c.surface.enabled && Object.values(c.surface.edgeWidths).some((v) => v !== 0)
      ? visibleSideBounds(mattePixels, W, H)
      : null;
  const columns = c.gridSize,
    rows = c.gridSize,
    out = ctx.createImageData(W, H),
    coverage = new Uint8Array(W * H);
  const [cropX, cropY, cropWidth, cropHeight] = c.sourceRect;
  const pixel = (u, v, channel) => {
    u = cropX + Math.max(0, Math.min(1, u)) * cropWidth;
    v = cropY + Math.max(0, Math.min(1, v)) * cropHeight;
    const sx = Math.max(0, Math.min(design.width - 1, u * (design.width - 1))),
      sy = Math.max(0, Math.min(design.height - 1, v * (design.height - 1)));
    const x = Math.floor(sx),
      y = Math.floor(sy),
      x1 = Math.min(x + 1, design.width - 1),
      y1 = Math.min(y + 1, design.height - 1),
      fx = sx - x,
      fy = sy - y;
    return (
      (design.data[(y * design.width + x) * 4 + channel] * (1 - fx) +
        design.data[(y * design.width + x1) * 4 + channel] * fx) *
        (1 - fy) +
      (design.data[(y1 * design.width + x) * 4 + channel] * (1 - fx) +
        design.data[(y1 * design.width + x1) * 4 + channel] * fx) *
        fy
    );
  };
  const points = c.points.map((p) => [p[0] * W, p[1] * H]);
  for (let gy = 0; gy < rows - 1; gy++)
    for (let gx = 0; gx < columns - 1; gx++) {
      const a = gy * columns + gx,
        b = a + 1,
        d = a + columns,
        z = d + 1;
      for (const ids of [
        [a, b, z],
        [a, z, d],
      ]) {
        const [p, q, r] = ids.map((i) => points[i]),
          den = (q[1] - r[1]) * (p[0] - r[0]) + (r[0] - q[0]) * (p[1] - r[1]);
        const uv = ids.map((i) => [
          (i % columns) / (columns - 1),
          Math.floor(i / columns) / (rows - 1),
        ]);
        const derivative = (axis) => [
          (((q[1] - r[1]) * uv[0][axis] +
            (r[1] - p[1]) * uv[1][axis] +
            (p[1] - q[1]) * uv[2][axis]) /
            den) *
            W,
          (((r[0] - q[0]) * uv[0][axis] +
            (p[0] - r[0]) * uv[1][axis] +
            (q[0] - p[0]) * uv[2][axis]) /
            den) *
            H,
        ];
        const du = derivative(0),
          dv = derivative(1);
        const minX = Math.max(0, Math.floor(Math.min(p[0], q[0], r[0]))),
          maxX = Math.min(W - 1, Math.ceil(Math.max(p[0], q[0], r[0]))),
          minY = Math.max(0, Math.floor(Math.min(p[1], q[1], r[1]))),
          maxY = Math.min(H - 1, Math.ceil(Math.max(p[1], q[1], r[1])));
        for (let y = minY; y <= maxY; y++)
          for (let x = minX; x <= maxX; x++) {
            const aa = ((q[1] - r[1]) * (x + 0.5 - r[0]) + (r[0] - q[0]) * (y + 0.5 - r[1])) / den,
              bb = ((r[1] - p[1]) * (x + 0.5 - r[0]) + (p[0] - r[0]) * (y + 0.5 - r[1])) / den,
              cc = 1 - aa - bb;
            if (Math.min(aa, bb, cc) < -1e-7) continue;
            let u = 0,
              v = 0;
            [aa, bb, cc].forEach((f, j) => {
              u += (f * (ids[j] % columns)) / (columns - 1);
              v += (f * Math.floor(ids[j] / columns)) / (rows - 1);
            });
            const surface = numericSides
              ? sideBounds
                ? visibleSideLight(c.surface, sideBounds, x, y, scale)
                : flatSurface
              : c.surface.enabled
                ? surfaceLight(c.surface, u, v)
                : flatSurface;
            const originalSurface =
              legacyOriginalSurface ||
              (numericSides && surface.weight > 0 && c.surface.source === 'background');
            if (numericSides && surface.weight > 0 && !originalSurface) {
              u += (surface.dx * du[0]) / W + (surface.dy * du[1]) / H;
              v += (surface.dx * dv[0]) / W + (surface.dy * dv[1]) / H;
            }
            const phase = v * Math.PI * 2 * c.foldFrequency + u * 2,
              bend = c.fold * Math.sin(phase);
            u += bend;
            const i = (y * W + x) * 4,
              luminance = (base.data[i] + base.data[i + 1] + base.data[i + 2]) / 3 / 242;
            if (field) {
              fabricAt(field, c.fabric, x, y, luma(base.data, i), material);
              // Convert the screen-space fold flow through this triangle's inverse UV basis.
              u += Math.max(-0.03, Math.min(0.03, material.dx * du[0] + material.dy * du[1]));
              v += Math.max(-0.03, Math.min(0.03, material.dx * dv[0] + material.dy * dv[1]));
            }
            const noise = (Math.sin(x * 12.9898 + y * 78.233) * 43758.5453) % 1;
            const shade =
              // Black is a replacement marker, not the material's lighting.
              (originalSurface
                ? 1
                : c.fabric.enabled
                  ? material.shade
                  : c.blackMask.enabled || c.greenMask.enabled
                    ? 1
                    : 1 - c.texture + c.texture * Math.min(1.1, luminance)) *
              (1 + c.plush * noise * 0.16) *
              (1 + c.fold * 2 * Math.cos(phase)) *
              surface.shade;
            const alpha = pixel(u, v, 3) / 255;
            for (let ch = 0; ch < 3; ch++) {
              const colour =
                view === 'lighting'
                  ? 160
                  : originalSurface
                    ? base.data[i + ch]
                    : pixel(u, v, ch) * alpha + 255 * (1 - alpha);
              const lit = Math.max(0, Math.min(255, colour * shade));
              out.data[i + ch] =
                view === 'coverage'
                  ? [38, 190, 147][ch]
                  : lit + (255 - lit) * (1 - (1 - material.sheen) * (1 - surface.sheen));
            }
            out.data[i + 3] = 255;
            coverage[y * W + x] = 1;
          }
      }
    }
  const layer = make(W, H),
    lc = layer.getContext('2d', { willReadFrequently: true });
  lc.putImageData(out, 0, 0);
  // Extend colour one pixel into the AA footprint, without leaving white fringes.
  const expanded = lc.getImageData(0, 0, W, H);
  const gutter = c.edgeRefine.enabled ? 0 : 1;
  const offsets = c.edgeRefine.enabled
    ? [
        [-1, 0],
        [1, 0],
        [0, -1],
        [0, 1],
        [-1, -1],
        [1, -1],
        [-1, 1],
        [1, 1],
      ]
    : [
        [-1, 0],
        [1, 0],
        [0, -1],
        [0, 1],
      ];
  for (let y = gutter; y < H - gutter; y++)
    for (let x = gutter; x < W - gutter; x++)
      if (!coverage[y * W + x]) {
        if (c.edgeRefine.enabled && !mattePixels[(y * W + x) * 4 + 3]) continue;
        let near;
        for (const [dx, dy] of offsets) {
          if (x + dx < 0 || x + dx >= W || y + dy < 0 || y + dy >= H) continue;
          const j = (y + dy) * W + x + dx;
          if (coverage[j]) {
            near = j;
            break;
          }
        }
        if (near !== undefined)
          for (let k = 0; k < 4; k++) expanded.data[(y * W + x) * 4 + k] = out.data[near * 4 + k];
      }
  lc.putImageData(expanded, 0, 0);
  // Restrict low-opacity gray watermark to rendered textile, not the source artwork or furniture.
  const w = c.watermark;
  if (w.text && w.opacity && view === 'composite') {
    lc.save();
    lc.fillStyle = `rgba(120,120,120,${w.opacity})`;
    lc.font = `${w.size * scale}px sans-serif`;
    lc.textAlign = 'center';
    lc.translate(W / 2, H / 2);
    lc.rotate((w.angle * Math.PI) / 180);
    for (let y = -H; y < H; y += w.spacing * scale)
      for (let x = -W; x < W; x += w.spacing * 1.7 * scale) lc.fillText(w.text, x, y);
    lc.restore();
  }
  const artwork = repairs.length && view !== 'coverage' ? lc.getImageData(0, 0, W, H) : null;
  lc.globalCompositeOperation = 'destination-in';
  lc.drawImage(matte, 0, 0);
  // Small edge softness is an alpha-only blur, never a blur of artwork/furniture.
  if (c.edgeSoftness > 1 && !c.edgeRefine.enabled) {
    const alpha = mc.getImageData(0, 0, W, H),
      lp = lc.getImageData(0, 0, W, H);
    for (let y = 1; y < H - 1; y++)
      for (let x = 1; x < W - 1; x++) {
        const i = (y * W + x) * 4 + 3;
        if (lp.data[i]) {
          const avg =
            (alpha.data[i - 4] +
              alpha.data[i + 4] +
              alpha.data[i - W * 4] +
              alpha.data[i + W * 4]) /
            4;
          lp.data[i] = Math.min(
            lp.data[i],
            Math.round(lp.data[i] + (avg - lp.data[i]) * (c.edgeSoftness - 1) * 0.2),
          );
        }
      }
    lc.putImageData(lp, 0, 0);
  }
  if (artwork) {
    const lp = lc.getImageData(0, 0, W, H);
    decontaminateEdges(lp.data, artwork.data, base.data, repairs);
    lc.putImageData(lp, 0, 0);
  }
  ctx.drawImage(layer, 0, 0);
  const fabricWarning =
    field && !field.active
      ? field.reason === 'empty'
        ? '未识别到足够布料，请检查范围、黑色阈值或蒙版。'
        : '原图光影层次不足，已保持原色；纯黑标记无法恢复真实褶皱，请上传保留明暗的原背景。'
      : null;
  return [edgeWarning, fabricWarning].filter(Boolean).join(' ') || null;
}
