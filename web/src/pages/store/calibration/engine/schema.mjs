import { defaultEdgeCurves, curvedMesh, hasEdgeCurves } from './curvature.mjs';
import { SIDE_EDGES, maxSideWidth } from './side-bands.mjs';
export const ENGINE = 'declarative-canvas-v1';
export const MAX_REGIONS = 8;
// Relative to the background, not the editor gutter. Rendering stays clipped to the image.
export const POINT_MIN = -0.25;
export const POINT_MAX = 1.25;
// Opt-in for stored snapshots. The editor enables this on newly created regions.
export const defaultEdgeRefine = (enabled = false) => ({ enabled, radius: 2 });
export const defaultSurface = (enabled = false) => ({
  enabled,
  source: 'design',
  lightEdge: 'left',
  darkness: 0.22,
  gradient: 0.2,
  highlight: 0.06,
  highlightWidth: 0.12,
});
export const defaultFabric = (enabled = false) => ({
  enabled,
  shadow: 0.72,
  highlight: 0.4,
  detail: 0.55,
  exposure: 1.05,
  smoothing: 8,
  displacement: 0.008,
});
export const defaultBlackMask = (enabled = false) => ({
  enabled,
  maxBrightness: 24,
  maxChroma: 12,
  softness: 8,
});
export const defaultGreenMask = (enabled = false) => ({
  enabled,
  maxDeviation: 12,
  softness: 8,
});
export const defaultWhiteMask = (enabled = false) => ({
  enabled,
  minBrightness: 160,
  maxChroma: 32,
  softness: 12,
});
export function gridFromCorners(corners, size = 5) {
  return Array.from({ length: size * size }, (_, i) => {
    const u = (i % size) / (size - 1),
      v = Math.floor(i / size) / (size - 1);
    return [0, 1].map(
      (axis) =>
        (1 - v) * ((1 - u) * corners[0][axis] + u * corners[1][axis]) +
        v * ((1 - u) * corners[3][axis] + u * corners[2][axis]),
    );
  });
}
export function defaultCalibration(width = 1254, height = 1254) {
  return {
    schemaVersion: 1,
    engine: ENGINE,
    width,
    height,
    gridSize: 5,
    points: gridFromCorners([
      [0.15, 0.35],
      [0.85, 0.35],
      [0.9, 0.85],
      [0.1, 0.85],
    ]),
    exclusions: [],
    whiteMask: defaultWhiteMask(),
    blackMask: defaultBlackMask(),
    greenMask: defaultGreenMask(),
    cornerRadius: 0,
    edgeSoftness: 0.8,
    edgeRefine: defaultEdgeRefine(),
    texture: 0.65,
    plush: 0.12,
    fold: 0,
    foldFrequency: 5,
    fabric: defaultFabric(),
    surface: defaultSurface(),
    edgeCurves: defaultEdgeCurves(),
    sourceRect: [0, 0, 1, 1],
    watermark: { text: 'WATERMARK', opacity: 0.065, size: 24, spacing: 180, angle: -25 },
  };
}
const keys = [
  'schemaVersion',
  'engine',
  'width',
  'height',
  'gridSize',
  'points',
  'exclusions',
  'whiteMask',
  'blackMask',
  'greenMask',
  'cornerRadius',
  'edgeSoftness',
  'edgeRefine',
  'texture',
  'plush',
  'fold',
  'foldFrequency',
  'fabric',
  'surface',
  'edgeCurves',
  'sourceRect',
  'watermark',
];
const number = (v, min, max, name) => {
  if (!Number.isFinite(v) || v < min || v > max) throw Error(`${name} 超出范围 ${min}–${max}`);
  return v;
};
const point = (p, min = 0, max = 1) => {
  if (!Array.isArray(p) || p.length !== 2) throw Error('坐标必须是 [x,y]');
  return p.map((v) => number(v, min, max, '归一化坐标'));
};
const cross = (a, b, c) => (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
export function validateCalibration(input) {
  if (input?.schemaVersion === 2) return validateMultiCalibration(input);
  if (
    !input ||
    Object.keys(input).some((k) => !keys.includes(k)) ||
    input.schemaVersion !== 1 ||
    input.engine !== ENGINE
  )
    throw Error('仅接受 declarative-canvas-v1 参数，不执行上传脚本。');
  const c = structuredClone(input);
  if (c.edgeRefine === undefined) c.edgeRefine = defaultEdgeRefine();
  if (
    !c.edgeRefine ||
    Object.keys(c.edgeRefine).some((k) => !['enabled', 'radius'].includes(k)) ||
    typeof c.edgeRefine.enabled !== 'boolean'
  )
    throw Error('边缘净化参数无效');
  number(c.edgeRefine.radius, 1, 3, '边缘净化半径');
  if (c.edgeCurves === undefined) c.edgeCurves = defaultEdgeCurves();
  if (
    !c.edgeCurves ||
    Object.keys(c.edgeCurves).some((k) => !Object.hasOwn(defaultEdgeCurves(), k))
  )
    throw Error('四边弧度参数无效');
  for (const edge of Object.keys(defaultEdgeCurves()))
    number(c.edgeCurves[edge], -0.3, 0.3, '四边弧度 ' + edge);
  if (c.surface === undefined) c.surface = defaultSurface();
  const surface = c.surface;
  if (
    !surface ||
    Object.keys(surface).some(
      (k) => ![...Object.keys(defaultSurface()), 'edgeWidths'].includes(k),
    ) ||
    typeof surface.enabled !== 'boolean' ||
    !['design', 'background'].includes(surface.source) ||
    !['left', 'right', 'top', 'bottom'].includes(surface.lightEdge)
  )
    throw Error('侧面光影参数无效');
  number(surface.darkness, 0, 0.8, '侧面暗度');
  number(surface.gradient, 0, 0.8, '侧面渐变');
  number(surface.highlight, 0, 0.4, '侧面亮边');
  number(surface.highlightWidth, 0.01, 0.5, '亮边宽度');
  if (surface.edgeWidths !== undefined) {
    if (
      !surface.edgeWidths ||
      Array.isArray(surface.edgeWidths) ||
      Object.keys(surface.edgeWidths).length !== 4 ||
      Object.keys(surface.edgeWidths).some((k) => !SIDE_EDGES.includes(k))
    )
      throw Error('侧面宽度必须包含上右下左四个边');
    for (const edge of SIDE_EDGES) {
      number(surface.edgeWidths[edge], -maxSideWidth(c), maxSideWidth(c), '侧面宽度（原图像素）');
      if (!Number.isInteger(surface.edgeWidths[edge])) throw Error('侧面宽度需为整数像素');
    }
  }
  if (c.fabric === undefined) c.fabric = defaultFabric();
  const fabric = c.fabric;
  if (
    !fabric ||
    Object.keys(fabric).some((k) => !Object.keys(defaultFabric()).includes(k)) ||
    typeof fabric.enabled !== 'boolean'
  )
    throw Error('布料光影参数无效');
  for (const key of ['shadow', 'highlight', 'detail']) number(fabric[key], 0, 1, '布料光影 ' + key);
  number(fabric.exposure, 0.5, 1.6, '布料亮度');
  number(fabric.smoothing, 1, 30, '布料光影平滑');
  number(fabric.displacement, 0, 0.03, '布料随褶皱位移');
  if (c.sourceRect === undefined) c.sourceRect = [0, 0, 1, 1];
  if (!Array.isArray(c.sourceRect) || c.sourceRect.length !== 4)
    throw Error('取图范围必须是 [x,y,width,height]');
  [0, 1, 2, 3].forEach((i) => number(c.sourceRect[i], i < 2 ? 0 : 0.001, 1, '取图范围'));
  if (
    c.sourceRect[0] + c.sourceRect[2] > 1.00000001 ||
    c.sourceRect[1] + c.sourceRect[3] > 1.00000001
  )
    throw Error('取图范围不能超出设计图');
  // Optional addition to v1; older saved templates remain square-cornered.
  c.cornerRadius ??= 0;
  // Old snapshots must retain their original full-area rendering.
  if (c.whiteMask === undefined) c.whiteMask = defaultWhiteMask();
  const wm = c.whiteMask;
  if (
    !wm ||
    Object.keys(wm).some(
      (k) => !['enabled', 'minBrightness', 'maxChroma', 'softness'].includes(k),
    ) ||
    typeof wm.enabled !== 'boolean'
  )
    throw Error('白色识别参数无效');
  number(wm.minBrightness, 80, 250, '白色最低亮度');
  number(wm.maxChroma, 0, 120, '白色色差容许值');
  number(wm.softness, 1, 32, '白色边缘过渡');
  if (c.blackMask === undefined) c.blackMask = defaultBlackMask();
  const bm = c.blackMask;
  if (
    !bm ||
    Object.keys(bm).some(
      (k) => !['enabled', 'maxBrightness', 'maxChroma', 'softness'].includes(k),
    ) ||
    typeof bm.enabled !== 'boolean'
  )
    throw Error('黑色识别参数无效');
  number(bm.maxBrightness, 0, 120, '黑色最高亮度');
  number(bm.maxChroma, 0, 60, '黑色色差容许值');
  number(bm.softness, 1, 32, '黑色边缘过渡');
  if (c.greenMask === undefined) c.greenMask = defaultGreenMask();
  const gm = c.greenMask;
  if (
    !gm ||
    Object.keys(gm).some((k) => !['enabled', 'maxDeviation', 'softness'].includes(k)) ||
    typeof gm.enabled !== 'boolean'
  )
    throw Error('绿色识别参数无效');
  number(gm.maxDeviation, 0, 120, '绿色最大偏差');
  number(gm.softness, 1, 32, '绿色边缘过渡');
  if (Number(bm.enabled) + Number(wm.enabled) + Number(gm.enabled) > 1)
    throw Error('黑色、绿色和旧版白色识别不能同时开启，只能选择一种');
  number(c.cornerRadius, 0, 0.5, '圆角比例');
  number(c.width, 64, 4096, '背景宽度');
  number(c.height, 64, 4096, '背景高度');
  if (!Number.isInteger(c.width) || !Number.isInteger(c.height) || c.width * c.height > 8_000_000)
    throw Error('背景最多 800 万像素，宽高必须是整数');
  if (
    ![2, 3, 5, 7, 9, 11].includes(c.gridSize) ||
    !Array.isArray(c.points) ||
    c.points.length !== c.gridSize ** 2
  )
    throw Error('网格点数量无效');
  c.points = c.points.map((p) => point(p, POINT_MIN, POINT_MAX));
  for (let y = 0; y < c.gridSize - 1; y++)
    for (let x = 0; x < c.gridSize - 1; x++) {
      const a = y * c.gridSize + x,
        b = a + 1,
        d = a + c.gridSize,
        z = d + 1;
      if (
        cross(c.points[a], c.points[b], c.points[z]) < 0.000005 ||
        cross(c.points[a], c.points[z], c.points[d]) < 0.000005
      )
        throw Error('网格发生交叉、翻转或挤压，请恢复相邻控制点');
    }
  if (hasEdgeCurves(c)) {
    const mesh = curvedMesh(c),
      n = mesh.gridSize,
      p = mesh.points;
    if (p.some((q) => q.some((v) => v < POINT_MIN || v > POINT_MAX)))
      throw Error('弧线超出画外操作范围，请减小弧度或向内移动四角');
    for (let y = 0; y < n - 1; y++)
      for (let x = 0; x < n - 1; x++) {
        const a = y * n + x,
          b = a + 1,
          d = a + n,
          z = d + 1;
        if (cross(p[a], p[b], p[z]) < 1e-10 || cross(p[a], p[z], p[d]) < 1e-10)
          throw Error('弧度导致区域折叠或交叉，请减小弧度');
      }
  }
  if (!Array.isArray(c.exclusions) || c.exclusions.length > 60)
    throw Error('最多支持 60 个家具遮挡多边形');
  c.exclusions = c.exclusions.map((poly) => {
    if (!Array.isArray(poly) || poly.length < 3 || poly.length > 100)
      throw Error('遮挡区域需 3–100 个点');
    return poly.map((p) => point(p));
  });
  number(c.edgeSoftness, 0.3, 3, '边缘抗锯齿');
  number(c.texture, 0, 1, '原背景纹理');
  number(c.plush, 0, 1, '毛绒颗粒');
  number(c.fold, 0, 0.1, '褶皱强度');
  number(c.foldFrequency, 1, 20, '褶皱频率');
  const w = c.watermark;
  if (
    !w ||
    Object.keys(w).some((k) => !['text', 'opacity', 'size', 'spacing', 'angle'].includes(k)) ||
    typeof w.text !== 'string' ||
    w.text.length > 40
  )
    throw Error('水印内容最多 40 个字符');
  number(w.opacity, 0, 0.3, '水印透明度');
  number(w.size, 8, 80, '水印字号');
  number(w.spacing, 60, 600, '水印间距');
  number(w.angle, -90, 90, '水印角度');
  return c;
}

// v2 shares one background/mask and contains ordered, independent v1 region parameters.
const sceneKeys = ['schemaVersion', 'engine', 'width', 'height', 'regions'];
const regionKeys = keys.filter((k) => !['schemaVersion', 'engine', 'width', 'height'].includes(k));
export function regionCalibration(scene, region) {
  const { id, name, enabled, ...parameters } = region;
  return {
    schemaVersion: 1,
    engine: scene.engine,
    width: scene.width,
    height: scene.height,
    ...parameters,
  };
}
export function multiCalibration(entries) {
  const { width, height } = entries[0].config;
  return {
    schemaVersion: 2,
    engine: ENGINE,
    width,
    height,
    regions: entries.map(({ id, name, enabled, config }) => {
      if (config.width !== width || config.height !== height)
        throw Error('各区域必须共用同尺寸背景');
      const { schemaVersion, engine, width: w, height: h, ...parameters } = config;
      return { id, name, enabled, ...parameters };
    }),
  };
}
function validateMultiCalibration(input) {
  if (input.engine !== ENGINE || Object.keys(input).some((k) => !sceneKeys.includes(k)))
    throw Error('多区域配置只接受固定引擎和声明式参数');
  if (
    !Array.isArray(input.regions) ||
    input.regions.length < 1 ||
    input.regions.length > MAX_REGIONS
  )
    throw Error(`每张背景需有 1–${MAX_REGIONS} 个区域`);
  const ids = new Set();
  const regions = input.regions.map((region, index) => {
    if (
      !region ||
      Object.keys(region).some((k) => !['id', 'name', 'enabled', ...regionKeys].includes(k)) ||
      typeof region.id !== 'string' ||
      !/^[a-zA-Z0-9_-]{1,64}$/.test(region.id) ||
      ids.has(region.id) ||
      typeof region.name !== 'string' ||
      !region.name.trim() ||
      region.name.length > 40 ||
      typeof region.enabled !== 'boolean'
    )
      throw Error(`区域 ${index + 1} 的名称、ID或配置无效`);
    ids.add(region.id);
    try {
      const { schemaVersion, engine, width, height, ...parameters } = validateCalibration(
        regionCalibration(input, region),
      );
      return { id: region.id, name: region.name, enabled: region.enabled, ...parameters };
    } catch (error) {
      throw Error(`区域 ${index + 1}（${region.name}）：${error.message}`, { cause: error });
    }
  });
  if (!regions.some((r) => r.enabled)) throw Error('至少启用一个渲染区域');
  const scene = {
    schemaVersion: 2,
    engine: ENGINE,
    width: input.width,
    height: input.height,
    regions,
  };
  if (new TextEncoder().encode(JSON.stringify(scene)).length > 64000)
    throw Error('多区域参数 JSON 最多 64 KB');
  return scene;
}
