const smooth = (lo, hi, value) => {
  const t = Math.max(0, Math.min(1, (value - lo) / (hi - lo)));
  return t * t * (3 - 2 * t);
};

// RGB(0,255,0) is the marker. Small deviations allow resampled image edges.
export function greenCoverage(r, g, b, settings) {
  const difference = Math.max(r, b, 255 - g);
  return 1 - smooth(settings.maxDeviation, settings.maxDeviation + settings.softness, difference);
}
