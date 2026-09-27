// Evaluate the ORIGINAL background, never the artwork. This is a color key,
// not semantic furniture segmentation. It only affects visibility, never UVs.
const smooth = (lo, hi, value) => {
  const t = Math.max(0, Math.min(1, (value - lo) / (hi - lo)));
  return t * t * (3 - 2 * t);
};
export function whiteCoverage(r, g, b, settings) {
  const brightness = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  const chroma = Math.max(r, g, b) - Math.min(r, g, b);
  return (
    smooth(settings.minBrightness - settings.softness, settings.minBrightness, brightness) *
    (1 - smooth(settings.maxChroma, settings.maxChroma + settings.softness, chroma))
  );
}
