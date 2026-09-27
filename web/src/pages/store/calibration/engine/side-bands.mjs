import { surfaceLight } from './surface.mjs';

export const SIDE_EDGES = ['top', 'right', 'bottom', 'left'];
export const defaultSideWidths = () => ({ top: 0, right: 0, bottom: 0, left: 0 });
export const maxSideWidth = (c) => Math.min(256, Math.floor(Math.min(c.width, c.height) / 2));

// Final coverage, AFTER geometry, colour key and PNG. Scan the outermost visible
// extent per row/column, not every run: furniture holes must not acquire a bevel.
// O(W*H) construction, O(W+H) storage; no new meshes or changes to artwork UV.
export function visibleSideBounds(alpha, width, height) {
  const left = new Int32Array(height).fill(width),
    right = new Int32Array(height).fill(-1);
  const top = new Int32Array(width).fill(height),
    bottom = new Int32Array(width).fill(-1);
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      if (!alpha[(y * width + x) * 4 + 3]) continue;
      left[y] = Math.min(left[y], x);
      right[y] = Math.max(right[y], x);
      top[x] = Math.min(top[x], y);
      bottom[x] = Math.max(bottom[x], y);
    }
  return { left, right, top, bottom };
}

// Up/down are measured vertically and left/right horizontally in original pixels.
// At corners use the strongest normalized band once, never multiply four shadows.
export function visibleSideLight(surface, bounds, x, y, scale = 1) {
  const distances = {
    top: y - bounds.top[x] + 0.5,
    right: bounds.right[y] - x + 0.5,
    bottom: bounds.bottom[x] - y + 0.5,
    left: x - bounds.left[y] + 0.5,
  };
  let selected = null,
    ratio = Infinity,
    remaining = 0;
  for (const edge of SIDE_EDGES) {
    const signed = surface.edgeWidths[edge];
    const width = Math.abs(signed) * scale,
      distance = signed < 0 ? distances[edge] : -distances[edge];
    if (width > 0 && distance > 0 && distance < width + 0.5 && distance / width < ratio) {
      selected = edge;
      ratio = distance / width;
      remaining = width - distance + 0.5;
    }
  }
  if (!selected) return { shade: 1, sheen: 0, weight: 0, dx: 0, dy: 0 };
  const light = surfaceLight({ ...surface, lightEdge: 'left' }, Math.max(0, 1 - ratio), 0);
  const weight = Math.min(1, remaining);
  // Reflect design UV across the band's inner seam, not the selection edge.
  // Positive candidates lie outside final coverage and remain clipped by its mask.
  const reflection = surface.edgeWidths[selected] < 0 ? 2 * Math.max(0, remaining - 0.5) : 0;
  return {
    shade: 1 + (light.shade - 1) * weight,
    sheen: light.sheen * weight,
    weight,
    dx: selected === 'left' ? reflection : selected === 'right' ? -reflection : 0,
    dy: selected === 'top' ? reflection : selected === 'bottom' ? -reflection : 0,
  };
}
