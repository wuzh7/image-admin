// Recover relative shading from the original textile, not from the artwork.
// This is a bounded 2D approximation: brightness is not a measured 3D height map.
const clamp = (v, min = 0, max = 1) => Math.max(min, Math.min(max, v));
export const luma = (data, i) => data[i] * 0.2126 + data[i + 1] * 0.7152 + data[i + 2] * 0.0722;

function blurAxis(src, width, height, radius, horizontal) {
  const out = new Float32Array(src.length);
  const rows = horizontal ? height : width,
    count = horizontal ? width : height;
  const at = (row, col) => (horizontal ? row * width + col : col * width + row);
  for (let row = 0; row < rows; row++) {
    let sum = 0;
    for (let k = 0; k <= Math.min(radius, count - 1); k++) sum += src[at(row, k)];
    for (let col = 0; col < count; col++) {
      out[at(row, col)] = sum;
      if (col - radius >= 0) sum -= src[at(row, col - radius)];
      if (col + radius + 1 < count) sum += src[at(row, col + radius + 1)];
    }
  }
  return out;
}

export function extractFabric(base, alpha, settings, scale = 1) {
  const W = base.width,
    H = base.height,
    reduction = Math.min(1, 1024 / Math.max(W, H));
  const width = Math.max(1, Math.round(W * reduction)),
    height = Math.max(1, Math.round(H * reduction));
  const sums = new Float32Array(width * height),
    weights = new Float32Array(sums.length);
  const histogram = new Float64Array(256);
  let total = 0;
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++) {
      const i = (y * W + x) * 4,
        weight = alpha[i + 3] / 255;
      if (weight < 0.5) continue; // Exclude feather/fringe pixels and non-textile objects.
      const L = luma(base.data, i),
        j =
          Math.min(height - 1, Math.floor((y * height) / H)) * width +
          Math.min(width - 1, Math.floor((x * width) / W));
      sums[j] += L * weight;
      weights[j] += weight;
      histogram[Math.round(L)] += weight;
      total += weight;
    }
  const percentile = (p) => {
    let count = 0;
    for (let i = 0; i < 256; i++) {
      count += histogram[i];
      if (count >= total * p) return i;
    }
    return 255;
  };
  const low = percentile(0.05),
    high = percentile(0.95),
    range = high - low;
  if (!total || range < 4) return { active: false, reason: !total ? 'empty' : 'flat', range };
  const radius = Math.max(1, Math.round(settings.smoothing * scale * reduction));
  const smoothSums = blurAxis(
    blurAxis(sums, width, height, radius, true),
    width,
    height,
    radius,
    false,
  );
  const smoothWeights = blurAxis(
    blurAxis(weights, width, height, radius, true),
    width,
    height,
    radius,
    false,
  );
  const mid = clamp(percentile(0.55), low + range * 0.2, high - range * 0.2);
  for (let j = 0; j < smoothSums.length; j++)
    smoothSums[j] = smoothWeights[j] > 0.0001 ? smoothSums[j] / smoothWeights[j] : mid;
  return {
    active: true,
    width,
    height,
    W,
    H,
    low,
    high,
    mid,
    range,
    light: smoothSums,
    valid: weights,
    radius,
  };
}

function sample(field, x, y) {
  const px = clamp(x, 0, field.width - 1),
    py = clamp(y, 0, field.height - 1);
  const x0 = Math.floor(px),
    y0 = Math.floor(py),
    x1 = Math.min(x0 + 1, field.width - 1),
    y1 = Math.min(y0 + 1, field.height - 1);
  const fx = px - x0,
    fy = py - y0;
  return (
    (field.light[y0 * field.width + x0] * (1 - fx) + field.light[y0 * field.width + x1] * fx) *
      (1 - fy) +
    (field.light[y1 * field.width + x0] * (1 - fx) + field.light[y1 * field.width + x1] * fx) * fy
  );
}

// Reuse 'out' in the pixel loop: avoid one allocation per output pixel.
export function fabricAt(field, settings, x, y, originalLuma, out) {
  if (!field?.active) {
    out.shade = 1;
    out.sheen = out.dx = out.dy = 0;
    return out;
  }
  const px = ((x + 0.5) * field.width) / field.W - 0.5,
    py = ((y + 0.5) * field.height) / field.H - 0.5;
  const L = sample(field, px, py);
  const shadow = clamp((field.mid - L) / (field.mid - field.low));
  const highlight = clamp((L - field.mid) / (field.high - field.mid));
  const detail = clamp((originalLuma - L) / field.range, -0.3, 0.3) * settings.detail;
  out.shade = clamp((1 - settings.shadow * shadow + detail * 0.9) * settings.exposure, 0.12, 1.8);
  out.sheen = clamp(highlight * settings.highlight * 0.45 + Math.max(0, detail) * 0.18, 0, 0.6);
  out.dx = out.dy = 0;
  if (settings.displacement) {
    const step = Math.max(2, field.radius * 2);
    const neighbor = (xx, yy) => {
      const ix = Math.round(clamp(xx, 0, field.width - 1)),
        iy = Math.round(clamp(yy, 0, field.height - 1));
      return field.valid[iy * field.width + ix] > 0 ? sample(field, xx, yy) : L;
    };
    out.dx =
      clamp((neighbor(px + step, py) - neighbor(px - step, py)) / field.range, -1, 1) *
      settings.displacement;
    out.dy =
      clamp((neighbor(px, py + step) - neighbor(px, py - step)) / field.range, -1, 1) *
      settings.displacement;
  }
  return out;
}
