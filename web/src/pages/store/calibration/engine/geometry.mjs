import { gridFromCorners } from './schema.mjs';

export const cornersOf = (c) => {
  const n = c.gridSize;
  return [0, n - 1, n * n - 1, n * (n - 1)].map((i) => [...c.points[i]]);
};
export function selectionCorners(a, b) {
  const [left, right] = [Math.min(a[0], b[0]), Math.max(a[0], b[0])];
  const [top, bottom] = [Math.min(a[1], b[1]), Math.max(a[1], b[1])];
  return [
    [left, top],
    [right, top],
    [right, bottom],
    [left, bottom],
  ];
}
export function rectangleGrid(points, size) {
  return gridFromCorners(
    selectionCorners(
      [Math.min(...points.map((p) => p[0])), Math.min(...points.map((p) => p[1]))],
      [Math.max(...points.map((p) => p[0])), Math.max(...points.map((p) => p[1]))],
    ),
    size,
  );
}
export function dragCorner(
  c,
  index,
  point,
  { lock = false, snap = false, tolerance = [0, 0] } = {},
) {
  const corners = cornersOf(c),
    n = c.gridSize;
  const k = [0, n - 1, n * n - 1, n * (n - 1)].indexOf(index);
  if (k < 0) return c.points;
  const q = [...point],
    across = [1, 0, 3, 2][k],
    vertical = [3, 2, 1, 0][k];
  if (snap && !lock) {
    if (Math.abs(q[0] - corners[vertical][0]) <= tolerance[0]) q[0] = corners[vertical][0];
    if (Math.abs(q[1] - corners[across][1]) <= tolerance[1]) q[1] = corners[across][1];
  }
  if (lock) {
    // Keep the opposite corner fixed; do not flip identities when crossing it.
    const opposite = corners[(k + 2) % 4],
      gap = Math.max(0.006, (n - 1) * 0.0025);
    q[0] = [0, 3].includes(k)
      ? Math.min(q[0], opposite[0] - gap)
      : Math.max(q[0], opposite[0] + gap);
    q[1] = [0, 1].includes(k)
      ? Math.min(q[1], opposite[1] - gap)
      : Math.max(q[1], opposite[1] + gap);
    return gridFromCorners(selectionCorners(q, opposite), n);
  }
  corners[k] = q;
  return gridFromCorners(corners, n);
}

// Same triangle interpolation as the inverse UV rasterizer, not a separate perspective approximation.
export function mapUV(c, u, v) {
  const n = c.gridSize,
    x = Math.min(n - 2, Math.floor(u * (n - 1))),
    y = Math.min(n - 2, Math.floor(v * (n - 1)));
  const a = y * n + x,
    s = u * (n - 1) - x,
    t = v * (n - 1) - y;
  const ids = s >= t ? [a, a + 1, a + n + 1] : [a, a + n + 1, a + n];
  const weights = s >= t ? [1 - s, s - t, t] : [1 - t, s, t - s];
  return [0, 1].map((axis) => ids.reduce((sum, id, j) => sum + c.points[id][axis] * weights[j], 0));
}
export function roundedContour(c) {
  const corners = cornersOf(c),
    distance = (a, b) => Math.hypot((a[0] - b[0]) * c.width, (a[1] - b[1]) * c.height);
  const w = (distance(corners[0], corners[1]) + distance(corners[3], corners[2])) / 2;
  const h = (distance(corners[0], corners[3]) + distance(corners[1], corners[2])) / 2;
  const radius = (c.cornerRadius || 0) * Math.min(w, h),
    rx = radius / w,
    ry = radius / h;
  const arcs = [
    [1 - rx, ry, -Math.PI / 2],
    [1 - rx, 1 - ry, 0],
    [rx, 1 - ry, Math.PI / 2],
    [rx, ry, Math.PI],
  ];
  const uv = [];
  for (let k = 0; k < 4; k++) {
    const [cx, cy, angle] = arcs[k];
    for (let i = 0; i <= 24; i++) {
      const a = angle + ((i / 24) * Math.PI) / 2;
      uv.push([cx + rx * Math.cos(a), cy + ry * Math.sin(a)]);
    }
    // Sample straight edges too: their path may be bent by boundary mesh points.
    const end = uv.at(-1),
      next = arcs[(k + 1) % 4];
    const start = [next[0] + rx * Math.cos(next[2]), next[1] + ry * Math.sin(next[2])];
    for (let i = 1; i < 32; i++) uv.push(end.map((p, axis) => p + ((start[axis] - p) * i) / 32));
  }
  return uv.map(([u, v]) => mapUV(c, Math.max(0, Math.min(1, u)), Math.max(0, Math.min(1, v))));
}
