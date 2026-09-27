// Material-space gradient follows the fitted side rather than the screen's horizontal axis.
export function surfaceLight(surface, u, v) {
  if (!surface.enabled) return { shade: 1, sheen: 0 };
  const t = Math.max(
    0,
    Math.min(1, { left: u, right: 1 - u, top: v, bottom: 1 - v }[surface.lightEdge]),
  );
  return {
    shade: Math.max(0.05, (1 - surface.darkness) * (1 - surface.gradient * t)),
    sheen: surface.highlight * Math.exp((-3 * t) / surface.highlightWidth),
  };
}
