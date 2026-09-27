import { MAX_REGIONS, POINT_MIN, POINT_MAX } from '../engine/schema.mjs';
import { displayedPoints, hasEdgeCurves } from '../engine/curvature.mjs';
import { SIDE_EDGES, defaultSideWidths, maxSideWidth } from '../engine/side-bands.mjs';
import { VIEW_BOX, PAPER_OFFSET, PAPER_SIZE } from './viewport.mjs';
import { escapeHtml as e } from './escape.mjs';

// Navigation is editor session state, never part of a saved rendering configuration.
export const CALIBRATION_TABS = [
  ['assets', '素材', '背景、测试图与模板信息'],
  ['shape', '形状', '区域、四边弧度与精确坐标'],
  ['edges', '边缘', '黑色 / 绿色识别、边缘净化与精细蒙版'],
  ['material', '材质', '毛绒颗粒、布料褶皱与侧面光影'],
  ['watermark', '水印', '浅灰水印的文字与样式'],
];
const button = (action, text, attrs = '', primary = false) =>
  `<button type="button" class="button small${primary ? ' primary' : ''}" data-action="cal:${action}" ${attrs}>${text}</button>`;
const help = (text) => `<p class="cal-help">${text}</p>`;
const check = (id, label, on) =>
  `<label class="cal-checkbox"><input id="${id}" type="checkbox" ${on ? 'checked' : ''}/>${label}</label>`;
const slider = (key, label, value, min, max, step) =>
  `<label class="cal-slider">${label}<output id="cal-value-${key}">${value}</output><input type="range" aria-label="${label}" data-cal-param="${key}" min="${min}" max="${max}" step="${step}" value="${value}"/></label>`;
const disclosure = (id, title, content, open = false) =>
  `<details class="cal-disclosure" id="cal-detail-${id}" data-cal-detail="${id}" ${open ? 'open' : ''}><summary>${title}</summary><div class="cal-disclosure-body">${content}</div></details>`;
const card = (title, body, tag = '') =>
  `<section class="cal-card"><div class="cal-card-title"><h3>${title}</h3>${tag ? `<span class="cal-tag">${tag}</span>` : ''}</div>${body}</section>`;
const upload = (id, title, accept, status) =>
  `<label class="cal-upload"><span>${title}<span class="cal-upload-status">${status}</span></span><input id="${id}" type="file" accept="${accept}"/></label>`;

function assetsPanel(m) {
  return `${card(
    '开始校准',
    help('先上传背景与一张测试设计图，再到画布上贴合产品区域。') +
      upload(
        'cal-background',
        '01 · 背景图片',
        'image/png,image/jpeg,image/webp',
        m.background ? '已加载' : '必选',
      ) +
      upload(
        'cal-design',
        '02 · 测试设计图',
        'image/png,image/jpeg,image/webp',
        m.design ? '已加载' : '仅用于预览',
      ) +
      (m.background
        ? help(`${e(m.background.name || '当前背景')} · ${m.config.width} × ${m.config.height}`)
        : '') +
      button('panel', '下一步：调整形状 →', 'data-id="shape"', true),
  )}
    ${card('模板信息', `<label>名称<input id="cal-name" maxlength="80" value="${e(m.name)}"/></label><label>商品类目<select id="cal-category">${(m.categories || []).map((cat) => `<option value="${e(cat.id)}" ${String(m.category) === String(cat.id) ? 'selected' : ''}>${e(cat.name)}</option>`).join('')}</select></label>`)}`;
}

function shapePanel(m) {
  const c = m.config,
    r = m.activeRegion,
    index = m.regions.indexOf(r),
    points = displayedPoints(c);
  return `${disclosure('region-properties', '区域设置 · 名称 / 复制 / 排序', `<label>当前区域名称<input id="cal-region-name" maxlength="40" value="${e(r.name)}"/></label>${check('cal-region-enabled', '参与渲染', r.enabled)}${help('画布上方切换区域；当前参数只影响所选区域。')}${disclosure('region-management', '复制、排序与删除', `<div class="calibration-tools">${button('region-copy', '复制当前区域', m.regions.length >= MAX_REGIONS ? 'disabled' : '')}${button('region-back', '后移一层', index === 0 ? 'disabled' : '')}${button('region-forward', '前移一层', index === m.regions.length - 1 ? 'disabled' : '')}${button('region-delete', '删除当前区域', m.regions.length === 1 ? 'disabled' : '')}</div>${help('所有区域合成一张成品。列表从下层到上层，后面的区域覆盖前面的重叠部分；Ctrl+Z 可撤销。')}`)}`)}
    ${card('贴合与对齐', `${help('斜面直接拖四角；需要褶皱时切换“网格弯曲”。')}${check('cal-snap', '吸附对齐（按住 Alt 临时关闭）', m.snap)}${check('cal-lock', '矩形锁定（仅正视图）', m.lockRectangle)}${button('align', '四角矩形对齐')}${slider('cornerRadius', '圆角比例（短边）', c.cornerRadius || 0, 0, 0.5, 0.005)}${help('0 为直角；0.05 约为短边的 5%。圆角随透视变形，不规则轮廓可用精细蒙版。')}`)}
    ${disclosure('curves', '四边弧度 · 杯身 / 曲面', help('正值外鼓、负值内收。0.10 约为边长 10% 的中点偏移，不是角度；四角固定，内部图案同步弯曲。') + ['top', 'right', 'bottom', 'left'].map((edge, i) => slider('edgeCurves.' + edge, ['上边', '右边', '下边', '左边'][i] + '弧度', c.edgeCurves[edge], -0.3, 0.3, 0.005)).join('') + button('curves-reset', '四边恢复平直') + help('杯身上下沿向下弯可从上边 −0.05、下边 0.05 开始；不是自动 3D 建模。'), hasEdgeCurves(c))}
    ${disclosure('grid', '控制网格', `<label>控制网格<select id="cal-grid">${[2, 3, 5, 7, 9, 11].map((n) => `<option value="${n}" ${c.gridSize === n ? 'selected' : ''}>${n} × ${n}</option>`).join('')}</select></label>${help('改变密度会按四角重建并清除手动内部弯曲；四边弧度单独保留。')}`)}
    ${disclosure('coordinates', '控制点精确坐标（像素）', `<label>控制点<select id="cal-point">${c.points.map((_p, i) => `<option value="${i}" ${m.selectedPoint === i ? 'selected' : ''}>第 ${Math.floor(i / c.gridSize) + 1} 行 / 第 ${(i % c.gridSize) + 1} 列</option>`).join('')}</select></label><div class="cal-field-pair">${['X', 'Y'].map((axis, i) => `<label>${axis}<input id="cal-${axis.toLowerCase()}" type="number" min="${POINT_MIN * (i ? c.height : c.width)}" max="${POINT_MAX * (i ? c.height : c.width)}" step="0.1" value="${(points[m.selectedPoint][i] * (i ? c.height : c.width)).toFixed(1)}"/></label>`).join('')}</div>${help('输入实时更新，金色点标记当前位置。允许坐标超出背景 −25%～125%；画外内容自动裁切。')}`)}
    ${disclosure('source', '设计取图范围（局部 / 放大细节）', help('默认铺完整设计图；局部放大时缩小宽高，再设置起点。起点＋宽高不能超过 100%。') + `<div class="cal-source-fields">${['起点 X', '起点 Y', '宽度', '高度'].map((label, i) => `<label>${label} %<input id="cal-source-${i}" type="number" min="${i < 2 ? 0 : 0.1}" max="100" step="0.1" value="${+(c.sourceRect[i] * 100).toFixed(4)}"/></label>`).join('')}</div>` + button('source-reset', '恢复完整设计图'))}`;
}

function edgesPanel(m) {
  const c = m.config,
    edge = c.edgeRefine;
  return `${card('颜色区域识别', check('cal-black-only', '仅渲染范围内的黑色部分', c.blackMask.enabled) + check('cal-green-only', '仅渲染范围内的纯绿色 RGB(0,255,0) 部分', c.greenMask.enabled) + help('黑色与绿色二选一。先按完整四角铺图，再隐藏非标记色；遮挡不会压缩图案比例。') + disclosure('black-thresholds', '黑色阈值 · 漏选 / 误选时调整', slider('blackMask.maxBrightness', '黑色最高亮度', c.blackMask.maxBrightness, 0, 120, 1) + slider('blackMask.maxChroma', '黑色色差容许值', c.blackMask.maxChroma, 0, 60, 1) + slider('blackMask.softness', '黑色边缘过渡', c.blackMask.softness, 1, 32, 1) + help(c.fabric.enabled ? '真实布料请保留明暗。需让阈值覆盖灰色褶皱高光，并用精细蒙版排除误选的家具。' : '纯黑标记需要包含完整产品和封边。提高亮度可能误选深灰家具，请配合“渲染范围”检查。')) + disclosure('green-thresholds', '绿色阈值 · 漏选 / 误选时调整', slider('greenMask.maxDeviation', '距离纯绿的最大通道偏差', c.greenMask.maxDeviation, 0, 120, 1) + slider('greenMask.softness', '绿色边缘过渡', c.greenMask.softness, 1, 32, 1) + help('0/255/0 会完整渲染；少量颜色偏差用于图片缩放后的边缘。仅建议使用无损 PNG 背景，避免 JPG 压缩使绿色污染相邻区域。')) + (c.exclusions.length ? help('开启颜色识别后不使用旧遮挡多边形。') : ''))}
    ${card('曲面边缘净化', check('cal-edge-refine-enabled', '净化标记色过渡边 / 曲线抗锯齿', edge.enabled) + button('edge-refine-preset', '应用曲面边缘优化') + (edge.enabled ? slider('edgeRefine.radius', '净化半径（原图像素）', edge.radius, 1, 3, 0.5) : '') + help('先用默认 2px，细边可用 1px。仅净化局部标记色混合边，不全局放宽阈值；PNG 蒙版仍限制范围。') + disclosure('edge-advanced', '边缘高级设置', slider('edgeSoftness', '边缘柔和（像素）', c.edgeSoftness, 0.3, 3, 0.1) + help(edge.enabled ? '净化开启时停用旧透明度柔化，避免再次露出底色。若仍有宽色条，请先调整四角 / 弧线覆盖完整产品。' : '历史模板保持原效果；启用净化可减少底色残留，支持撤销。')), edge.enabled ? '已开启' : '未开启')}
    ${disclosure('mask', '精细蒙版 PNG（可选）', upload('cal-mask', '精细蒙版 PNG', 'image/png', m.mask ? '已加载' : '同背景尺寸') + help('白色允许贴图，黑色 / 透明保留原背景，灰色柔化边缘。它与背景的“黑色待替换”含义相反。') + button('remove-mask', '移除精细蒙版', m.mask ? '' : 'disabled') + disclosure('mask-help', '精细蒙版 PNG 是什么？', help('它是与背景同尺寸、同位置的显隐控制图，不是设计图。比如产品和杯把都为黑色：把蒙版杯把处涂黑、产品面涂白，即可保护杯把。它不改变拉伸比例，多区域共用。') + help('PNG 与颜色识别取交集。完全手工控制时可关闭黑色识别，仅用 PNG。复杂反折、自遮挡仍需要分区域及蒙版；这里不是家具语义识别。')), Boolean(m.mask))}`;
}

function materialPanel(m) {
  const c = m.config,
    f = c.fabric,
    s = c.surface;
  const fabricSliders = f.enabled
    ? `${slider('fabric.shadow', '褶皱阴影', f.shadow, 0, 1, 0.02)}${slider('fabric.highlight', '绒面高光', f.highlight, 0, 1, 0.02)}${slider('fabric.detail', '原图纤维细节', f.detail, 0, 1, 0.01)}${slider('fabric.exposure', '布料整体亮度', f.exposure, 0.5, 1.6, 0.05)}${disclosure('fabric-advanced', '光影平滑与微变形', slider('fabric.smoothing', '光影平滑（原图像素）', f.smoothing, 1, 30, 1) + slider('fabric.displacement', '随褶皱微变形', f.displacement, 0, 0.03, 0.001) + help('微变形按原图明暗偏移，不是 3D 还原；大的折弯仍需网格，反折用独立区域。'))}`
    : '';
  const legacySide = s.edgeWidths === undefined;
  const widths = s.edgeWidths || defaultSideWidths();
  const sideSliders = `<label>侧面内容<select id="cal-surface-source"><option value="design" ${s.source === 'design' ? 'selected' : ''}>${legacySide ? '设计图侧面' : '镜像包边（设计图）'}</option><option value="background" ${s.source === 'background' ? 'selected' : ''}>保留原木纹（只调光影）</option></select></label>${legacySide ? `<label>亮边位置<select id="cal-surface-edge">${SIDE_EDGES.map((edge) => `<option value="${edge}" ${s.lightEdge === edge ? 'selected' : ''}>${{ top: '上', right: '右', bottom: '下', left: '左' }[edge]}边</option>`).join('')}</select></label>` : help('以黑色识别 / PNG 后的实际可见外沿为基准，内侧接缝提亮，外沿渐暗。')}
    ${slider('surface.darkness', '侧面整体暗度', s.darkness, 0, 0.8, 0.01)}${slider('surface.gradient', '由亮到暗的渐变', s.gradient, 0, 0.8, 0.01)}${slider('surface.highlight', '边缘高光', s.highlight, 0, 0.4, 0.01)}${slider('surface.highlightWidth', '高光宽度比例', s.highlightWidth, 0.01, 0.5, 0.01)}`;
  return `${card('毛绒与颗粒', slider('plush', '毛绒颗粒度', c.plush, 0, 1, 0.02) + help('地毯、绒毯可适当增加；杯子与平面印刷品通常设为 0。'))}
    ${card('真实布料光影', check('cal-fabric-enabled', '保留原图褶皱与高光', f.enabled) + button('fabric-preset', '应用黑色绒毯预设') + help('毛毯 / 被子请上传保留明暗的原背景，不要先涂成纯黑。预设会调整识别阈值，四角和水印不变。') + fabricSliders, f.enabled ? '已开启' : '')}
    ${disclosure(
      'surface',
      '木框 / 包边侧面光影',
      (legacySide
        ? help(
            '这是旧版独立侧面，保留原位置和效果。新的四边宽度请切换到正面区域设置；不再提供手动新增侧面的入口。',
          )
        : help(
            '负数向实际可渲染边缘内侧形成镜像包边，如 −5 = 向内 5px。不是从框选四角起算；0 关闭，正数向外但仍受渲染蒙版裁切。',
          )) +
        check(
          'cal-surface-enabled',
          legacySide ? '保留旧区域侧面光影' : '启用四边侧面光影',
          s.enabled,
        ) +
        (legacySide
          ? ''
          : `<div class="cal-side-widths">${SIDE_EDGES.map((edge) => `<label>${{ top: '上', right: '右', bottom: '下', left: '左' }[edge]}边宽度 <span>px</span><input id="cal-side-${edge}" aria-label="${{ top: '上', right: '右', bottom: '下', left: '左' }[edge]}侧面宽度（像素）" aria-describedby="cal-side-width-help" data-cal-side-width="${edge}" type="number" min="${-maxSideWidth(c)}" max="${maxSideWidth(c)}" step="1" value="${widths[edge]}"/></label>`).join('')}</div><p id="cal-side-width-help" class="cal-help">选中输入框：↑ / → 加 1px，↓ / ← 减 1px。非零自动启用，范围 −${maxSideWidth(c)}～${maxSideWidth(c)}px；与预览缩放无关。</p>`) +
        sideSliders +
        help(
          '四边共用暗度 / 高光。镜像仅改变包边带内的取图，内部主体比例不变；孔洞不另加阴影。非黑色木纹需关闭黑色识别并用 PNG 限定。切换底部“材质光影图”检查效果，不生成墙面投影。',
        ),
      s.enabled,
    )}
    ${disclosure('texture', '纹理与附加波纹', (!f.enabled && !c.blackMask.enabled && !c.greenMask.enabled ? slider('texture', '保留背景纹理 / 阴影', c.texture, 0, 1, 0.05) : help(f.enabled ? '真实光影已代替旧背景纹理乘色，附加波纹通常保持 0。' : '纯色标记不带真实光影，不使用原底色压暗或染色设计。')) + slider('fold', '附加规则波纹强度', c.fold, 0, 0.1, 0.002) + slider('foldFrequency', '附加规则波纹频率', c.foldFrequency, 1, 20, 1))}`;
}

function watermarkPanel(m) {
  const w = m.config.watermark;
  return `${card('浅灰水印', help('直接叠加到当前区域的 Canvas，批量套图自动带入，不修改设计原图。') + `<label>文字<input id="cal-watermark-text" maxlength="40" value="${e(w.text)}"/></label>` + slider('watermark.opacity', '可见程度', w.opacity, 0, 0.3, 0.005) + slider('watermark.size', '字号', w.size, 8, 80, 1) + slider('watermark.spacing', '间距', w.spacing, 60, 600, 10) + slider('watermark.angle', '角度', w.angle, -90, 90, 1) + help('可见程度为 0 或文字留空时不绘制。浅灰水印不应抢画面，但不能保证无法移除。'))}`;
}

export function calibrationView(m) {
  const c = m.config,
    tab = m.inspectorTab || 'assets';
  const hint = {
    select: '拖出选区 → 拖四角贴合斜边 → 拉伸铺满选区。',
    corners: '拖动四角贴合产品；Alt 暂停吸附。',
    mesh: '拖动内部点调整褶皱，控制点不能相互交叉。',
    move: '拖动画布，或按 ↑ ↓ ← →，每按一次移动 1 px。',
  }[m.tool];
  const panels = {
    assets: assetsPanel,
    shape: shapePanel,
    edges: edgesPanel,
    material: materialPanel,
    watermark: watermarkPanel,
  };
  const zooms = [...new Set([10, 20, 30, 40, 50, 75, 100, 150, 200, 300, m.zoom])].sort(
    (a, b) => a - b,
  );
  return `<div class="cal-editor ${m.expanded ? 'is-expanded' : ''}">
    <section class="cal-page-header"><p>上传素材 · 贴合区域 · 调整效果 · 验收发布</p><div class="cal-header-actions"><span class="cal-save-state" id="cal-save-state" role="status">${m.dirty ? '有未发布修改' : m.id ? '已发布' : '未发布'}</span>${button('expand', m.expanded ? '收起工作区' : '展开工作区', `aria-pressed="${Boolean(m.expanded)}"`)}${button('publish', m.saving ? '发布中…' : '验收并发布', m.saving ? 'disabled' : '', true)}</div></section>
    <div class="calibration-layout">
      <section class="panel calibration-work" aria-label="校准画布工作区">
        <div class="cal-canvas-toolbar"><div class="cal-toolset" role="group" aria-label="画布工具">${[
          ['select', '框选区域', '▧'],
          ['corners', '四角拉伸', '⌗'],
          ['mesh', '网格弯曲', '▦'],
          ['move', '整体移动', '↔'],
        ]
          .map(
            ([id, title, icon]) =>
              `<button type="button" class="cal-tool ${m.tool === id ? 'is-active' : ''}" aria-pressed="${m.tool === id}" data-action="cal:tool" data-id="${id}"><span aria-hidden="true">${icon}</span>${title}</button>`,
          )
          .join(
            '',
          )}</div><div class="cal-tool-utilities">${button('undo', '撤销', `${m.history.length ? '' : 'disabled'} title="Ctrl+Z"`)}${button('preview', m.preview ? '显示控制点' : '隐藏控制点')}</div></div>
        <section class="cal-regions" aria-label="渲染区域管理"><span class="cal-region-caption">区域 <b>${m.regions.length}</b></span><div class="cal-region-list" role="group" aria-label="选择要编辑的区域">${m.regions.map((r, i) => `<button type="button" class="cal-region-chip ${r.id === m.activeRegionId ? 'is-active' : ''}" data-action="cal:region-select" data-id="${e(r.id)}" aria-pressed="${r.id === m.activeRegionId}"><span>${i + 1}</span>${e(r.name)}${r.enabled ? '' : ' · 停用'}</button>`).join('')}</div>${button('region-add', '＋ 新增区域', m.regions.length >= MAX_REGIONS ? 'disabled' : '')}</section>
        <div class="cal-context-line"><span>${hint}</span><div>${button('fill', '拉伸铺满选区', `${!m.selection ? 'disabled' : ''} ${m.tool !== 'select' && !m.selection ? 'hidden' : ''}`, true)}${button('cancel', '取消操作', 'title="Esc"')}</div></div>
        <div class="cal-canvas-area ${m.background ? '' : 'is-empty'}"><div class="calibration-viewport"><div class="calibration-stage" style="width:${m.zoom}%;aspect-ratio:${c.width}/${c.height};--cal-paper-offset:${PAPER_OFFSET}%;--cal-paper-size:${PAPER_SIZE}%"><canvas id="cal-canvas" width="${c.width}" height="${c.height}" aria-label="校准实时渲染预览"></canvas><svg id="cal-overlay" viewBox="${VIEW_BOX}" preserveAspectRatio="none" aria-label="可拖动模板控制点，支持拖出背景边缘"></svg></div></div>${m.background ? '' : `<div class="cal-empty"><div class="cal-empty-mark" aria-hidden="true">▧</div><h2>从一张背景开始</h2><p>在右侧「素材」上传背景与测试图，<br/>再用四角或网格贴合需要渲染的区域。</p>${button('panel', '去上传素材 →', 'data-id="assets"', true)}<span>平面 · 曲面 · 地毯 · 褶皱布料</span></div>`}</div>
        <div class="cal-viewbar"><label>查看<select id="cal-material-view">${[
          ['composite', '成品预览'],
          ['original', '原始背景'],
          ['lighting', '材质光影图（布料 / 侧面）'],
          ['coverage', '渲染范围（绿色）'],
        ]
          .map(
            ([v, t]) =>
              `<option value="${v}" ${(m.materialView || 'composite') === v ? 'selected' : ''}>${t}</option>`,
          )
          .join(
            '',
          )}</select></label><label>预览缩放<select id="cal-zoom">${zooms.map((v) => `<option value="${v}" ${m.zoom === v ? 'selected' : ''}>${v}%</option>`).join('')}</select></label>${button('fit-view', '适应画布')}<span class="cal-dimensions">${c.width} × ${c.height}</span></div>
        <div class="cal-work-status"><span id="cal-preview-status" role="status">拖动快速预览 · 松手高清重绘</span><details class="cal-shortcuts" data-cal-detail="shortcuts"><summary>操作帮助 ⌨</summary><div><p><kbd>Ctrl Z</kbd> 撤销一步　<kbd>Esc</kbd> 取消操作</p><p><kbd>Ctrl + 滚轮</kbd> 缩放 10%～300%</p><p>整体移动模式：方向键每次移动原图 1px。</p><p>控制点可拖出图片四周 25%；画外留白不会进入成品。文字输入保留原生快捷键。</p><p>形状：坐标 / 弧度。边缘：净化 / 蒙版。材质：毛绒 / 侧面。</p></div></details></div>
        <p id="cal-material-status" class="cal-material-status" role="status">${e((m.materialWarnings || []).join(' '))}</p><p id="cal-error" class="error-text" role="status">${e(m.error)}</p>
      </section>
      <aside class="panel calibration-controls" aria-label="校准参数栏"><div class="cal-inspector-heading"><div><span>参数面板</span><strong id="cal-inspector-context">${e(m.activeRegion.name)}</strong></div><span class="cal-tag">${m.activeRegion.enabled ? '参与渲染' : '已停用'}</span></div>
        <div class="cal-tabs" role="tablist" aria-label="参数分类">${CALIBRATION_TABS.map(([id, label, desc]) => `<button type="button" role="tab" id="cal-tab-${id}" aria-controls="cal-panel-${id}" aria-selected="${tab === id}" tabindex="${tab === id ? 0 : -1}" data-action="cal:panel" data-id="${id}" title="${desc}">${label}</button>`).join('')}</div>
        ${CALIBRATION_TABS.map(([id]) => `<div class="cal-inspector-panel" role="tabpanel" id="cal-panel-${id}" aria-labelledby="cal-tab-${id}" ${tab === id ? '' : 'hidden'} tabindex="0">${panels[id](m)}</div>`).join('')}
        <div class="cal-inspector-footer">按区域独立配置 · 顶部验收并发布</div>
      </aside>
    </div>
    <div class="cal-review-note"><span>发布前检查</span>完整封边、家具保护、图案比例与水印 · 辅助视图不进入成品 · 发布后到模板组菜单组建套图</div>
  </div>`;
}
