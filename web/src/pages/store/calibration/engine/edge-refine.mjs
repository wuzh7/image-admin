// Local black-matte decontamination, NOT a global threshold increase or mask dilation.
// Recover C = a*K + (1-a)*O only near a confidently black pixel K, where the
// outward colour O is locally stable and all RGB channels agree with that mixture.
// Ambiguous silhouettes still need a correctly aligned region / precise PNG mask.
export const EDGE_CANDIDATE_LIMIT = 128000;
export function refineBlackEdges(base, geometry, keyed, settings, scale = 1, marker = 'black') {
  const { width: W, height: H, data: b } = base;
  // The preview resampling filter adds up to one destination pixel on each side.
  // Keep this footprint at fast scale; the final output always uses native-pixel radius.
  const radius = Math.max(scale < 1 ? 2 : 1, settings.radius * scale),
    limit = Math.ceil(radius);
  const candidates = new Map();
  const markerDistance = (at) =>
    marker === 'green'
      ? b[at * 4] + (255 - b[at * 4 + 1]) + b[at * 4 + 2]
      : b[at * 4] + b[at * 4 + 1] + b[at * 4 + 2];
  const core = (j) => keyed[j * 4 + 3] >= 250 && geometry[j * 4 + 3] >= 250;
  // Visit only a narrow frontier: O(image pixels + perimeter * radius²).
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++) {
      const j = y * W + x;
      if (!core(j)) continue;
      if (
        (x === 0 || core(j - 1)) &&
        (x === W - 1 || core(j + 1)) &&
        (y === 0 || core(j - W)) &&
        (y === H - 1 || core(j + W))
      )
        continue;
      for (let dy = -limit; dy <= limit; dy++)
        for (let dx = -limit; dx <= limit; dx++) {
          const d2 = dx * dx + dy * dy,
            px = x + dx,
            py = y + dy;
          if (!d2 || d2 > radius * radius || px < 0 || py < 0 || px >= W || py >= H) continue;
          const k = py * W + px;
          if (core(k) || !geometry[k * 4 + 3] || b[k * 4 + 3] !== 255) continue;
          const previous = candidates.get(k);
          if (
            !previous ||
            previous.d2 > d2 ||
            (previous.d2 === d2 && markerDistance(j) < markerDistance(previous.seed))
          )
            candidates.set(k, { seed: j, d2 });
          // Highly speckled black textures are not a silhouette. Keep worker memory bounded.
          if (candidates.size > EDGE_CANDIDATE_LIMIT) return { repairs: [], limited: true };
        }
    }
  const repairs = [];
  for (const [j, { seed, d2 }] of candidates) {
    const i = j * 4,
      k = seed * 4;
    const x = j % W,
      y = Math.floor(j / W),
      distance = Math.sqrt(d2);
    const nx = (x - (seed % W)) / distance,
      ny = (y - Math.floor(seed / W)) / distance;
    let previous = -1,
      outside = -1;
    // The two samples must plateau beyond the transition, not lie in a long grey ramp.
    for (let step = 1; step <= 4; step++) {
      const px = Math.round(x + nx * step),
        py = Math.round(y + ny * step);
      if (px < 0 || py < 0 || px >= W || py >= H) break;
      const q = (py * W + px) * 4;
      if (b[q + 3] !== 255) break;
      if (previous >= 0 && [0, 1, 2].every((ch) => Math.abs(b[q + ch] - b[previous + ch]) <= 6)) {
        outside = q;
        break;
      }
      previous = q;
    }
    if (outside < 0) continue;
    let dot = 0,
      magnitude = 0;
    for (let ch = 0; ch < 3; ch++) {
      const delta = b[outside + ch] - b[k + ch];
      dot += (b[i + ch] - b[k + ch]) * delta;
      magnitude += delta * delta;
    }
    if (magnitude < 3 * 40 ** 2) continue;
    const t = dot / magnitude,
      a = 1 - t;
    if (a < 0.015 || a > 0.995) continue;
    if (
      [0, 1, 2].some(
        (ch) => Math.abs(b[i + ch] - (b[k + ch] + t * (b[outside + ch] - b[k + ch]))) > 5,
      )
    )
      continue;
    // Never grow beyond geometry. The user PNG is intersected afterwards and is authoritative.
    keyed[i + 3] = Math.round(Math.min(geometry[i + 3], a * 255));
    repairs.push({ index: i, alpha: a, black: [b[k], b[k + 1], b[k + 2]] });
  }
  return { repairs, limited: false };
}

// Bake the recovered outside colour into just the fringe pixels. This avoids the
// second multiplication of a black-contaminated background by source-over alpha.
// PNG / geometry restrictions remain a separate coverage factor, including soft masks.
export function decontaminateEdges(layer, artwork, base, repairs) {
  for (const { index: i, alpha, black } of repairs) {
    if (!layer[i + 3] || !artwork[i + 3]) continue;
    const coverage = Math.min(1, layer[i + 3] / (255 * alpha));
    for (let ch = 0; ch < 3; ch++)
      layer[i + ch] = base[i + ch] + alpha * (artwork[i + ch] - black[ch]);
    layer[i + 3] = Math.round(255 * coverage);
  }
}
