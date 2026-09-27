const smooth = (lo, hi, value) => {
  const t = Math.max(0, Math.min(1, (value - lo) / (hi - lo)));
  return t * t * (3 - 2 * t);
};

// Use the brightest channel, not luminance: saturated dark colours are not neutral black.
export function blackCoverage(r, g, b, settings) {
  const brightness = Math.max(r, g, b);
  const chroma = brightness - Math.min(r, g, b);
  return (
    (1 - smooth(settings.maxBrightness, settings.maxBrightness + settings.softness, brightness)) *
    (1 - smooth(settings.maxChroma, settings.maxChroma + settings.softness, chroma))
  );
}
