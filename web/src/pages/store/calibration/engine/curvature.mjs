// Data-only geometry shared by validation, editor, preview worker and batch renderer.
export const defaultEdgeCurves = () => ({ top: 0, right: 0, bottom: 0, left: 0 });
export const hasEdgeCurves = (c) => Object.values(c.edgeCurves || {}).some((v) => v !== 0);

export function interpolateGrid(c, u, v) {
  const n = c.gridSize,
    x = Math.min(n - 2, Math.floor(u * (n - 1))),
    y = Math.min(n - 2, Math.floor(v * (n - 1))),
    a = y * n + x,
    s = u * (n - 1) - x,
    t = v * (n - 1) - y;
  const ids = s >= t ? [a, a + 1, a + n + 1] : [a, a + n + 1, a + n];
  const weights = s >= t ? [1 - s, s - t, t] : [1 - t, s, t - s];
  return [0, 1].map((axis) => ids.reduce((sum, id, j) => sum + c.points[id][axis] * weights[j], 0));
}

// Quadratic edge bulges blended through the interior (Coons-style offsets).
// Positive = outward. Values are midpoint bulge / physical edge length, not degrees.
export function curveOffset(c, u, v) {
  const n = c.gridSize,
    p = [0, n - 1, n * n - 1, n * (n - 1)].map((i) => c.points[i]);
  const weights = [
    4 * u * (1 - u) * (1 - v),
    4 * v * (1 - v) * u,
    4 * u * (1 - u) * v,
    4 * v * (1 - v) * (1 - u),
  ];
  const delta = [0, 0];
  ['top', 'right', 'bottom', 'left'].forEach((edge, i) => {
    const a = p[i],
      b = p[(i + 1) % 4],
      amount = (c.edgeCurves?.[edge] || 0) * weights[i];
    delta[0] += (((b[1] - a[1]) * c.height) / c.width) * amount;
    delta[1] -= (((b[0] - a[0]) * c.width) / c.height) * amount;
  });
  return delta;
}
export function displayedPoints(c) {
  return c.points.map((p, i) => {
    const d = curveOffset(
      c,
      (i % c.gridSize) / (c.gridSize - 1),
      Math.floor(i / c.gridSize) / (c.gridSize - 1),
    );
    return [p[0] + d[0], p[1] + d[1]];
  });
}
export function curvedMesh(c) {
  if (!hasEdgeCurves(c)) return c; // Preserve legacy pixels exactly at zero curvature.
  // Quadratic chord error scales with bulge / segments². Keep original grid knots,
  // and bound tessellation instead of supersampling the whole 8 MP background.
  const bulge =
      Math.max(c.width, c.height) * Math.max(...Object.values(c.edgeCurves).map(Math.abs)),
    target = c.edgeRefine?.enabled
      ? Math.min(96, Math.max(24, Math.ceil(Math.sqrt(bulge / 0.12))))
      : 24;
  const segments = (c.gridSize - 1) * Math.ceil(target / (c.gridSize - 1)),
    size = segments + 1;
  const points = Array.from({ length: size * size }, (_, i) => {
    const u = (i % size) / segments,
      v = Math.floor(i / size) / segments;
    const p = interpolateGrid(c, u, v),
      d = curveOffset(c, u, v);
    return [p[0] + d[0], p[1] + d[1]];
  });
  return { ...c, gridSize: size, points, edgeCurves: defaultEdgeCurves() };
}
