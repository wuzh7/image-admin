import {
  defaultCalibration,
  defaultFabric,
  defaultEdgeRefine,
  gridFromCorners,
  validateCalibration,
  MAX_REGIONS,
  POINT_MIN,
  POINT_MAX,
  multiCalibration,
} from '../engine/schema.mjs';
import {
  selectionCorners,
  rectangleGrid,
  dragCorner,
  roundedContour,
  cornersOf,
} from '../engine/geometry.mjs';
import {
  VIEW_SPAN,
  pointerPosition,
  snapTolerance,
  translatePoints,
  replaceCalibrationView,
  zoomFromWheel,
} from './viewport.mjs';
import {
  defaultEdgeCurves,
  curveOffset,
  curvedMesh,
  displayedPoints,
  hasEdgeCurves,
} from '../engine/curvature.mjs';
import { renderDeclarative } from '../engine/declarative.mjs';
import { calibrationView, CALIBRATION_TABS } from './view.mjs';
import { SIDE_EDGES, defaultSideWidths, maxSideWidth } from '../engine/side-bands.mjs';
import { escapeHtml as e } from './escape.mjs';

export class CalibrationManager {
  replaceView(root, html) {
    replaceCalibrationView(root, html);
  }
  get activeRegion() {
    return this.regions.find((r) => r.id === this.activeRegionId) || this.regions[0];
  }
  get config() {
    return this.activeRegion.config;
  }
  set config(value) {
    this.activeRegion.config = value;
  }
  sceneConfig() {
    return multiCalibration(this.regions);
  }
  selectRegion(id) {
    if (!this.regions.some((r) => r.id === id)) throw Error('区域不存在');
    this.cancelInteraction();
    this.stopPreview();
    this.activeRegionId = id;
    this.selection = null;
    this.selectedPoint = 0;
    this.lockRectangle = false;
    this.tool = 'corners';
    this.preview = false;
    this.materialView = 'composite';
    this.materialWarnings = [];
    this.error = '';
  }

  constructor(service, { render, flash, confirmPublish }) {
    Object.assign(this, { service, render, flash, confirmPublish });
    this.reset();
  }
  reset() {
    this.stopPreview();
    this.id = null;
    this.name = '新背景模板';
    this.category = this.categories?.[0]?.id ?? '';
    this.regions = [
      { id: 'region-1', name: '区域 1', enabled: true, config: defaultCalibration() },
    ];
    this.activeRegionId = 'region-1';
    this.config.blackMask.enabled = true;
    this.config.edgeRefine = defaultEdgeRefine(true);
    this.config.surface.edgeWidths = defaultSideWidths();
    this.background = null;
    this.backgroundImage = null;
    this.mask = null;
    this.maskImage = null;
    this.design = null;
    this.previewPixels = null;
    this.tool = 'corners';
    this.history = [];
    this.inspectorTab = 'assets';
    this.expanded ??= false;
    this.dirty = false;
    this.preview = false;
    this.materialView = 'composite';
    this.materialWarnings = [];
    this.error = '';
    this.zoom = 100;
    this.selectedPoint = 0;
    this.selection = null;
    this.lockRectangle = false;
    this.snap = true;
    this.assetRevision = (this.assetRevision || 0) + 1;
  }
  async loadPublished(record, background, mask) {
    this.reset();
    const saved = JSON.parse(record.calibrationConfigJson);
    this.id = record.id;
    this.name = record.name;
    this.category = record.categoryId;
    this.regions = saved.regions.map(({ id, name, enabled, ...parameters }) => ({
      id, name, enabled,
      config: { schemaVersion: 1, engine: saved.engine, width: saved.width, height: saved.height, ...parameters },
    }));
    this.activeRegionId = this.regions[0].id;
    this.background = background;
    this.backgroundImage = await this.decode(background);
    if (mask) { this.mask = mask; this.maskImage = await this.decode(mask); }
    this.assetRevision++;
  }
  stopPreview() {
    this.resizeObserver?.disconnect();
    clearTimeout(this.paintTimer);
    if (this.overlayFrame) cancelAnimationFrame(this.overlayFrame);
    this.overlayFrame = null;
    this.previewWorker?.terminate();
    this.previewWorker = null;
    this.workerHasAssets = false;
    this.workerBusy = false;
    this.pendingPreview = null;
    this.drag = null;
    this.interaction = null;
    this.previewSequence = (this.previewSequence || 0) + 1;
  }
  snapshot() {
    // Images/Blobs are immutable references; only editable data is cloned.
    const state = {};
    for (const key of [
      'id',
      'name',
      'category',
      'background',
      'backgroundImage',
      'mask',
      'maskImage',
      'design',
      'previewPixels',
      'tool',
      'preview',
      'materialView',
      'selectedPoint',
      'lockRectangle',
      'snap',
      'dirty',
      'activeRegionId',
    ])
      state[key] = this[key];
    return {
      ...state,
      config: structuredClone(this.config),
      regions: structuredClone(this.regions),
      selection: structuredClone(this.selection),
    };
  }
  restore(snapshot) {
    this.stopPreview();
    const { config, ...state } = snapshot;
    Object.assign(this, state, {
      regions: structuredClone(snapshot.regions),
      selection: structuredClone(snapshot.selection),
    });
    this.assetRevision++;
    this.error = '';
  }
  remember() {
    this.history.push(this.snapshot());
    if (this.history.length > 30) this.history.shift();
    this.dirty = true;
    const undo = this.root?.querySelector('[data-action="cal:undo"]');
    if (undo) undo.disabled = false;
    const state = this.root?.querySelector('#cal-save-state');
    if (state) state.textContent = '有未保存修改';
  }
  beginInteraction(kind) {
    if (this.interaction) return;
    this.interaction = { kind, before: this.snapshot(), history: [...this.history] };
    this.remember();
  }
  cancelInteraction() {
    if (!this.interaction) return false;
    const { before, history } = this.interaction;
    this.restore(before);
    this.history = history;
    this.render();
    return true;
  }
  bindShortcuts(doc) {
    if (this.keyDocument === doc) return;
    this.keyDocument?.removeEventListener('keydown', this.keyHandler);
    this.keyDocument = doc;
    this.keyHandler = (ev) => {
      if (
        ev.defaultPrevented ||
        ev.isComposing ||
        ev.repeat ||
        this.saving ||
        this.loading ||
        !this.root?.querySelector('#cal-overlay') ||
        doc.querySelector('dialog[open], [role="dialog"], .modal-overlay')
      )
        return;
      const target = ev.target;
      if (
        target?.closest?.('textarea, select, [contenteditable]:not([contenteditable="false"])') ||
        (target?.tagName === 'INPUT' && target.type !== 'range')
      )
        return;
      let action;
      const direction = {
        ArrowLeft: [-1, 0],
        ArrowRight: [1, 0],
        ArrowUp: [0, -1],
        ArrowDown: [0, 1],
      }[ev.key];
      if (
        direction &&
        this.tool === 'move' &&
        this.background &&
        !this.interaction &&
        !this.drag &&
        target?.tagName !== 'INPUT' &&
        !ev.ctrlKey &&
        !ev.metaKey &&
        !ev.altKey &&
        !ev.shiftKey
      ) {
        ev.preventDefault();
        this.moveByPixel(...direction);
        return;
      }
      if (
        (ev.ctrlKey || ev.metaKey) &&
        !ev.altKey &&
        !ev.shiftKey &&
        ev.key.toLowerCase() === 'z'
      ) {
        if (this.interaction || this.history.length) action = 'cal:undo';
      } else if (!ev.ctrlKey && !ev.metaKey && !ev.altKey && ev.key === 'Escape') {
        action = 'cal:cancel';
      }
      if (!action) return;
      ev.preventDefault();
      this.handle(action).catch((error) => this.flash(error.message));
    };
    doc.addEventListener('keydown', this.keyHandler);
  }
  moveByPixel(dx, dy) {
    // Normalized coordinates are measured against the original background, not CSS zoom.
    const delta = [dx / this.config.width, dy / this.config.height];
    const points = this.config.points.map((p) => [p[0] + delta[0], p[1] + delta[1]]);
    // At the workspace limit, refuse a step instead of silently moving a fraction of a pixel.
    if (points.some((p) => p.some((v) => v < POINT_MIN || v > POINT_MAX))) return;
    this.remember();
    this.config.points = points;
    this.error = '';
    this.drawOverlay();
    this.syncCoordinates();
    this.schedulePaint();
  }
  view() {
    this.selectedPoint = Math.min(this.selectedPoint, this.config.points.length - 1);
    return calibrationView(this);
  }
  openPanel(id, focus = false) {
    if (!CALIBRATION_TABS.some(([key]) => key === id)) return;
    this.inspectorTab = id;
    for (const tab of this.root?.querySelectorAll('[role="tab"][data-action="cal:panel"]') || []) {
      const active = tab.dataset.id === id;
      tab.setAttribute('aria-selected', String(active));
      tab.tabIndex = active ? 0 : -1;
      if (active && focus) tab.focus({ preventScroll: true });
    }
    for (const panel of this.root?.querySelectorAll('.cal-inspector-panel') || [])
      panel.hidden = panel.id !== `cal-panel-${id}`;
  }
  bindPanelNavigation(root) {
    for (const tab of root.querySelectorAll('[role="tab"][data-action="cal:panel"]')) {
      tab.onkeydown = (ev) => {
        const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End'];
        if (!keys.includes(ev.key)) return;
        ev.preventDefault();
        ev.stopPropagation();
        const index = CALIBRATION_TABS.findIndex(([id]) => id === tab.dataset.id);
        const next =
          ev.key === 'Home'
            ? 0
            : ev.key === 'End'
              ? CALIBRATION_TABS.length - 1
              : (index + (ev.key === 'ArrowRight' ? 1 : -1) + CALIBRATION_TABS.length) %
                CALIBRATION_TABS.length;
        this.openPanel(CALIBRATION_TABS[next][0], true);
      };
    }
  }
  fitView() {
    const viewport = this.root?.querySelector('.calibration-viewport');
    if (!viewport || !viewport.clientWidth || !viewport.clientHeight) return;
    // Include the outside editing gutter, leaving every edge handle reachable.
    const width = Math.max(1, viewport.clientWidth - 32);
    const height = Math.max(1, viewport.clientHeight - 32);
    const zoom = Math.max(
      10,
      Math.min(
        100,
        Math.floor((((height / width) * this.config.width) / this.config.height) * 100),
      ),
    );
    this.setZoom(zoom);
    viewport.scrollLeft = 0;
    viewport.scrollTop = 0;
  }
  showMaterialWarnings(warnings = []) {
    this.materialWarnings = warnings;
    const label = this.root?.querySelector('#cal-material-status');
    if (label) label.textContent = warnings.join(' ');
  }
  attach(root) {
    this.root = root;
    const overlay = root.querySelector('#cal-overlay');
    if (!overlay) return;
    this.bindShortcuts(root.ownerDocument);
    this.bindPanelNavigation(root);
    this.paint();
    this.drawOverlay();
    this.syncCoordinates();
    const viewport = root.querySelector('.calibration-viewport');
    if (viewport) {
      if (viewport.calWheelHandler) viewport.removeEventListener('wheel', viewport.calWheelHandler);
      viewport.calWheelHandler = (ev) => {
        if (!ev.ctrlKey) return;
        ev.preventDefault();
        if (this.drag || this.saving || this.loading) return;
        this.setZoom(zoomFromWheel(this.zoom, ev.deltaY, ev.deltaMode), ev);
      };
      viewport.addEventListener('wheel', viewport.calWheelHandler, { passive: false });
      viewport.title = 'Ctrl + 鼠标滚轮缩放（10%–300%），普通滚轮滚动画布';
    }
    this.resizeObserver?.disconnect();
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(() => this.drawOverlay());
      this.resizeObserver.observe(overlay);
    }
    const position = (ev) => pointerPosition(ev, overlay.getBoundingClientRect());
    overlay.onpointerdown = (ev) => {
      if (this.preview || this.saving || this.loading || ev.button > 0) return;
      ev.preventDefault();
      const p = position(ev),
        index = ev.target.dataset.index;
      const regionId = ev.target.dataset.regionId;
      if (regionId && regionId !== this.activeRegionId) {
        this.selectRegion(regionId);
        this.render();
        return;
      }
      if (this.tool === 'select') {
        this.beginInteraction('drag');
        const selectionCorner = ev.target.dataset.selectionIndex;
        const previousSelection = this.selection ? structuredClone(this.selection) : null;
        if (this.selection && selectionCorner !== undefined) {
          this.drag = {
            start: p,
            select: true,
            selectionCorner: Number(selectionCorner),
            previousSelection,
          };
        } else {
          this.selection = null;
          this.lockRectangle = false;
          root.querySelector('#cal-lock').checked = false;
          this.drag = { start: p, select: true, previousSelection };
          root.querySelector('[data-action="cal:fill"]').disabled = true;
        }
      } else {
        if (this.tool !== 'move' && index === undefined) return;
        this.beginInteraction('drag');
        if (index !== undefined) this.selectedPoint = Number(index);
        this.drag = { index: Number(index), start: p, points: structuredClone(this.config.points) };
      }
      this.drawOverlay();
      this.syncCoordinates();
      overlay.setPointerCapture?.(ev.pointerId);
    };
    overlay.onpointermove = (ev) => {
      if (!this.drag) return;
      const p = position(ev),
        { points, start, index } = this.drag;
      if (p[0] === start[0] && p[1] === start[1] && !this.drag.changed) return;
      if (!this.drag.changed) {
        // Interrupt only when movement actually begins, not on an ordinary click.
        this.previewWorker?.terminate();
        this.previewWorker = null;
        this.workerBusy = this.workerHasAssets = false;
        this.pendingPreview = null;
        this.previewSequence++;
      }
      this.drag.changed = true;
      if (this.drag.select) {
        if (this.drag.selectionCorner !== undefined) {
          const n = this.config.gridSize,
            box = overlay.getBoundingClientRect();
          const candidate = dragCorner(
            { ...this.config, points: gridFromCorners(this.drag.previousSelection, n) },
            [0, n - 1, n * n - 1, n * (n - 1)][this.drag.selectionCorner],
            p,
            {
              lock: this.lockRectangle,
              snap: this.snap && !ev.altKey,
              tolerance: snapTolerance(box),
            },
          );
          try {
            validateCalibration({ ...this.config, points: candidate });
            this.selection = cornersOf({ ...this.config, points: candidate });
            this.showError('');
          } catch {
            this.showError('选区角点不能交叉或翻转，请沿原来的四角顺序调整。');
            return;
          }
        } else this.selection = selectionCorners(start, p);
      } else if (this.tool === 'move') {
        this.config.points = translatePoints(points, [p[0] - start[0], p[1] - start[1]]);
      } else if (
        this.tool === 'corners' &&
        [
          0,
          this.config.gridSize - 1,
          this.config.gridSize ** 2 - 1,
          this.config.gridSize * (this.config.gridSize - 1),
        ].includes(index)
      ) {
        const box = overlay.getBoundingClientRect();
        this.config.points = dragCorner({ ...this.config, points }, index, p, {
          lock: this.lockRectangle,
          snap: this.snap && !ev.altKey,
          tolerance: snapTolerance(box),
        });
      } else {
        const n = this.config.gridSize,
          offset = curveOffset(this.config, (index % n) / (n - 1), Math.floor(index / n) / (n - 1));
        this.config.points[index] = p.map((v, axis) => v - offset[axis]);
      }
      if (!this.overlayFrame)
        this.overlayFrame = requestAnimationFrame(() => {
          this.overlayFrame = null;
          this.drawOverlay();
          this.syncCoordinates();
          if (!this.drag?.select) this.paint(true);
        });
    };
    const finish = (ev, cancelled = false) => {
      if (!this.drag) return;
      if (this.overlayFrame) cancelAnimationFrame(this.overlayFrame);
      this.overlayFrame = null;
      if (cancelled) {
        overlay.releasePointerCapture?.(ev.pointerId);
        this.cancelInteraction();
        return;
      }
      const changed = this.drag.changed;
      if (!changed && this.interaction) {
        this.history = this.interaction.history;
        this.dirty = this.interaction.before.dirty;
      }
      this.drag = null;
      this.interaction = null;
      overlay.releasePointerCapture?.(ev.pointerId);
      this.drawOverlay();
      this.syncCoordinates();
      const fill = root.querySelector('[data-action="cal:fill"]');
      if (fill) fill.disabled = !this.selection;
      const undo = root.querySelector('[data-action="cal:undo"]');
      if (undo) undo.disabled = !this.history.length;
      if (changed) this.paint();
    };
    overlay.onpointerup = (ev) => finish(ev);
    overlay.onpointercancel = (ev) => finish(ev, true);
    root.querySelectorAll('[data-cal-param]').forEach((el) => {
      if (el.dataset.calParam === 'edgeSoftness' && this.config.edgeRefine.enabled) {
        el.disabled = true;
        el.title = '曲面边缘净化已启用，自动处理边缘，不再使用旧透明度柔化';
      }
      el.onpointerdown = () => this.beginInteraction('slider');
      el.oninput = () => {
        this.beginInteraction('slider');
        this.dirty = true;
        const [a, b] = el.dataset.calParam.split('.');
        if (a === 'edgeCurves') {
          const curves = {
            ...(this.config.edgeCurves || defaultEdgeCurves()),
            [b]: Number(el.value),
          };
          try {
            validateCalibration({ ...this.config, edgeCurves: curves });
          } catch (error) {
            this.showError(error.message);
            el.value = this.config.edgeCurves[b];
            return;
          }
          this.config.edgeCurves = curves;
          this.showError('');
        } else if (b) this.config[a][b] = Number(el.value);
        else this.config[a] = Number(el.value);
        root.querySelector('[id="cal-value-' + el.dataset.calParam + '"]').textContent = el.value;
        this.drawOverlay();
        this.syncCoordinates();
        this.schedulePaint();
      };
      el.onchange = () => {
        this.interaction = null;
        clearTimeout(this.paintTimer);
        this.paint();
      };
      el.onpointerup = () => {
        this.interaction = null;
      };
      el.onpointercancel = () => this.cancelInteraction();
      el.onblur = () => {
        this.interaction = null;
      };
    });
    for (const el of root.querySelectorAll('#cal-x, #cal-y')) {
      el.oninput = () => this.updateCoordinate(el, true);
      el.onblur = () => {
        this.interaction = null;
        this.syncCoordinates();
        this.paint();
      };
    }
    for (const el of root.querySelectorAll('[data-cal-side-width]')) {
      el.oninput = () => this.updateSideWidth(el, true);
      el.onkeydown = (ev) => {
        const delta = { ArrowUp: 1, ArrowRight: 1, ArrowDown: -1, ArrowLeft: -1 }[ev.key];
        if (!delta) return;
        ev.preventDefault();
        ev.stopPropagation();
        if (
          ev.isComposing ||
          ev.repeat ||
          ev.ctrlKey ||
          ev.metaKey ||
          ev.altKey ||
          ev.shiftKey ||
          this.saving ||
          this.loading
        )
          return;
        this.interaction = null;
        const current = this.config.surface.edgeWidths?.[el.dataset.calSideWidth] || 0;
        const value = current + delta;
        if (value < -maxSideWidth(this.config) || value > maxSideWidth(this.config)) return;
        el.value = String(value);
        this.updateSideWidth(el);
      };
      el.onblur = () => {
        this.interaction = null;
        el.value = String(this.config.surface.edgeWidths?.[el.dataset.calSideWidth] || 0);
        this.paint();
      };
    }
  }
  updateSideWidth(target, live = false) {
    if (this.saving || this.loading || target.value === '' || target.validity?.badInput) return;
    const edge = target.dataset?.calSideWidth || target.id.replace('cal-side-', '');
    if (!SIDE_EDGES.includes(edge)) return;
    try {
      const value = Number(target.value),
        current = this.config.surface;
      const surface = {
        ...current,
        enabled: value !== 0 || current.enabled,
        edgeWidths: { ...defaultSideWidths(), ...current.edgeWidths, [edge]: value },
      };
      validateCalibration({ ...this.config, surface });
      if (current.edgeWidths?.[edge] === value && current.enabled === surface.enabled) return;
      if (live) this.beginInteraction('side-width');
      else this.remember();
      this.config.surface = surface;
      const checkbox = this.root?.querySelector('#cal-surface-enabled');
      if (checkbox) checkbox.checked = surface.enabled;
      this.showError('');
      this.drawOverlay();
      this.schedulePaint();
    } catch (error) {
      this.showError(error.message);
      if (!live) target.value = String(this.config.surface.edgeWidths?.[edge] || 0);
    }
  }
  setZoom(value, anchor = null) {
    if (!Number.isFinite(value)) return;
    const viewport = this.root?.querySelector('.calibration-viewport'),
      stage = viewport?.querySelector('.calibration-stage');
    const zoom = Math.max(10, Math.min(300, value));
    if (!stage) {
      this.zoom = zoom;
      return;
    }
    const old = stage.getBoundingClientRect(),
      box = viewport.getBoundingClientRect();
    const x = anchor?.clientX ?? box.left + viewport.clientWidth / 2;
    const y = anchor?.clientY ?? box.top + viewport.clientHeight / 2;
    const u = old.width ? (x - old.left) / old.width : 0.5,
      v = old.height ? (y - old.top) / old.height : 0.5;
    this.zoom = zoom;
    stage.style.width = zoom + '%';
    const next = stage.getBoundingClientRect();
    viewport.scrollLeft += next.left + u * next.width - x;
    viewport.scrollTop += next.top + v * next.height - y;
    const select = this.root.querySelector('#cal-zoom');
    if (select) {
      select.querySelector('[data-custom-zoom]')?.remove();
      if (![...select.options].some((o) => Number(o.value) === zoom)) {
        const option = select.ownerDocument.createElement('option');
        option.value = String(zoom);
        option.textContent = zoom + '%';
        option.dataset.customZoom = '';
        select.append(option);
      }
      select.value = String(zoom);
    }
    this.drawOverlay(); // View-only: no bitmap re-render or template/history mutation.
  }
  updateCoordinate(target, live = false) {
    if (this.saving || (live && this.loading)) return;
    if (target.value === '' || target.validity?.badInput) return;
    const c = this.config,
      n = c.gridSize,
      axis = target.id === 'cal-x' ? 0 : 1;
    const limit = axis ? c.height : c.width,
      value = Number(target.value),
      index = this.selectedPoint;
    try {
      if (!Number.isFinite(value) || value < POINT_MIN * limit || value > POINT_MAX * limit)
        throw Error(`坐标需在 ${POINT_MIN * limit}～${POINT_MAX * limit} 像素之间（含画外操作区）`);
      const offset = curveOffset(c, (index % n) / (n - 1), Math.floor(index / n) / (n - 1));
      const point = [...c.points[index]];
      point[axis] = value / limit - offset[axis];
      if (Math.abs(point[axis] - c.points[index][axis]) < 1e-12) return;
      const isCorner =
        this.tool === 'corners' && [0, n - 1, n * n - 1, n * (n - 1)].includes(index);
      const points = isCorner
        ? dragCorner(c, index, point, { lock: this.lockRectangle })
        : c.points.map((p, i) => (i === index ? point : [...p]));
      validateCalibration({ ...c, points });
      if (live) this.beginInteraction('coordinate');
      else if (!this.interaction) this.remember();
      this.config.points = points;
      if (!isCorner) {
        this.lockRectangle = false;
        const lock = this.root?.querySelector('#cal-lock');
        if (lock) lock.checked = false;
      }
      this.showError('');
      this.drawOverlay();
      this.syncCoordinates(live ? target : null);
      this.schedulePaint();
    } catch (error) {
      this.showError(error.message);
    }
  }
  syncCoordinates(except = null) {
    const q = displayedPoints(this.config)[this.selectedPoint];
    for (const [id, value] of [
      ['cal-point', this.selectedPoint],
      ['cal-x', (q[0] * this.config.width).toFixed(1)],
      ['cal-y', (q[1] * this.config.height).toFixed(1)],
    ]) {
      const el = this.root?.querySelector('#' + id);
      if (el && el !== except) el.value = value;
    }
  }
  schedulePaint() {
    clearTimeout(this.paintTimer);
    this.paint(true);
    this.paintTimer = setTimeout(() => this.paint(), 200);
  }
  testPixels() {
    if (this.previewPixels) return this.previewPixels;
    const test = document.createElement('canvas');
    test.width = this.design?.naturalWidth || 240;
    test.height = this.design?.naturalHeight || 240;
    const tc = test.getContext('2d', { willReadFrequently: true });
    if (this.design) tc.drawImage(this.design, 0, 0);
    else {
      tc.fillStyle = '#24594c';
      tc.fillRect(0, 0, 240, 240);
      tc.fillStyle = '#d3ab66';
      for (let y = 0; y < 240; y += 30)
        for (let x = 0; x < 240; x += 30) if ((x + y) % 60 === 0) tc.fillRect(x, y, 30, 30);
    }
    return (this.previewPixels = tc.getImageData(0, 0, test.width, test.height));
  }
  status(text) {
    const label = this.root?.querySelector('#cal-preview-status');
    if (label) label.textContent = text;
  }
  paint(fast = false) {
    const canvas = this.root?.querySelector('#cal-canvas');
    if (!canvas) return;
    const c = this.sceneConfig(),
      ctx = canvas.getContext('2d');
    if (!this.backgroundImage) {
      this.showMaterialWarnings([]);
      canvas.width = c.width;
      canvas.height = c.height;
      ctx.fillStyle = '#efeee8';
      ctx.fillRect(0, 0, c.width, c.height);
      ctx.fillStyle = '#737b78';
      ctx.font = c.width / 32 + 'px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('上传黑色替换区域的背景图开始校准', c.width / 2, c.height / 2);
      return;
    }
    try {
      validateCalibration(c);
      this.error = '';
      if (
        !this.previewWorker &&
        !this.workerUnavailable &&
        typeof Worker !== 'undefined' &&
        typeof OffscreenCanvas !== 'undefined'
      ) {
        this.workerHasAssets = false;
        try {
          this.previewWorker = new Worker(new URL('./preview.worker.mjs', import.meta.url), {
            type: 'module',
          });
        } catch {
          this.workerUnavailable = true;
          this.paint(fast);
          return;
        }
        const worker = this.previewWorker;
        this.previewWorker.onmessage = ({ data }) => {
          if (worker !== this.previewWorker) {
            data.bitmap?.close();
            return;
          }
          this.workerBusy = false;
          if (data.error) this.workerHasAssets = false;
          const current = this.root?.querySelector('#cal-canvas');
          if (
            current &&
            (data.id === this.previewSequence ||
              (data.fast && (this.drag || this.pendingPreview?.fast)))
          ) {
            if (data.error) {
              if (data.id === this.previewSequence) this.showError(data.error);
            } else {
              if (data.id === this.previewSequence) this.showError('');
              if (current.width !== data.bitmap.width) current.width = data.bitmap.width;
              if (current.height !== data.bitmap.height) current.height = data.bitmap.height;
              current.getContext('2d').drawImage(data.bitmap, 0, 0);
              this.showMaterialWarnings(data.warnings);
              this.status(
                data.fast
                  ? '快速预览 · 松手后恢复高清'
                  : '高清预览 · ' + c.width + ' × ' + c.height,
              );
            }
          }
          data.bitmap?.close();
          this.flushPreview();
        };
        this.previewWorker.onerror = () => {
          if (worker !== this.previewWorker) return;
          this.previewWorker?.terminate();
          this.previewWorker = null;
          this.workerBusy = false;
          this.workerUnavailable = true;
          this.pendingPreview = null;
          this.paint();
        };
      }
      if (this.previewWorker) {
        this.pendingPreview = {
          id: ++this.previewSequence,
          fast,
          config: structuredClone(c),
          view: this.materialView || 'composite',
          assetRevision: this.assetRevision,
        };
        this.status(fast ? '快速预览中…' : '后台生成高清预览…');
        this.flushPreview();
      } else {
        // Unsupported browsers never run a full-size pixel loop while dragging.
        if (this.drag) {
          this.status('兼容模式：松手后预览');
          return;
        }
        const scale = Math.min(1, 768 / Math.max(c.width, c.height));
        const buffer = canvas.ownerDocument.createElement('canvas');
        buffer.width = Math.round(c.width * scale);
        buffer.height = Math.round(c.height * scale);
        const result = renderDeclarative(
          buffer.getContext('2d', { willReadFrequently: true }),
          this.backgroundImage,
          this.testPixels(),
          c,
          this.maskImage,
          {
            scale,
            view: this.materialView || 'composite',
          },
        );
        if (canvas.width !== buffer.width) canvas.width = buffer.width;
        if (canvas.height !== buffer.height) canvas.height = buffer.height;
        ctx.drawImage(buffer, 0, 0);
        this.showMaterialWarnings(result.warnings);
        this.status('兼容预览（最长边 768px），导出仍使用原尺寸');
      }
    } catch (error) {
      this.showError(error.message);
    }
    this.showError(this.error);
  }
  flushPreview() {
    if (this.workerBusy || !this.pendingPreview || !this.previewWorker) return;
    const job = this.pendingPreview;
    this.pendingPreview = null;
    if (this.sentAssetRevision !== this.assetRevision || !this.workerHasAssets) {
      Object.assign(job, {
        background: this.background,
        mask: this.mask,
        design: this.testPixels(),
      });
      this.sentAssetRevision = this.assetRevision;
    }
    this.workerHasAssets = true;
    this.workerBusy = true;
    this.previewWorker.postMessage(job, []);
  }
  showError(message) {
    this.error = message;
    const label = this.root?.querySelector('#cal-error');
    if (label) label.textContent = message;
  }
  drawOverlay() {
    const svg = this.root?.querySelector('#cal-overlay');
    if (!svg) return;
    const c = this.config,
      n = c.gridSize,
      mesh = curvedMesh(c),
      m = mesh.gridSize,
      controls = displayedPoints(c),
      pt = (p) => `${p[0] * 1000},${p[1] * 1000}`;
    const box = svg.getBoundingClientRect();
    // Counteract SVG scaling so handles remain round and 16 CSS px at every zoom/aspect ratio.
    const handle = (p, attrs) =>
      `<circle r="8" transform="translate(${p[0] * 1000} ${p[1] * 1000}) scale(${(1000 * VIEW_SPAN) / (box.width || 1000)} ${(1000 * VIEW_SPAN) / (box.height || 1000)})" ${attrs}/>`;
    const lines = [];
    for (let i = 0; i < n; i++) {
      const k = Math.round((i * (m - 1)) / (n - 1));
      lines.push(mesh.points.slice(k * m, k * m + m));
      lines.push(Array.from({ length: m }, (_, j) => mesh.points[j * m + k]));
    }
    const regionOutlines = this.regions
      .map((r, index) => {
        const active = r.id === this.activeRegionId,
          point = r.config.points[0];
        return `${active ? '' : `<polygon class="cal-region-outline ${r.enabled ? '' : 'is-disabled'}" points="${roundedContour(curvedMesh(r.config)).map(pt).join(' ')}" data-region-id="${e(r.id)}"/>`}<text class="cal-region-label ${active ? 'is-active' : ''}" x="${point[0] * 1000}" y="${point[1] * 1000 - 18}" data-region-id="${e(r.id)}">${index + 1}. ${e(r.name)}${r.enabled ? '' : '（停用）'}</text>`;
      })
      .join('');
    svg.innerHTML = this.preview
      ? ''
      : `${regionOutlines}${lines.map((l) => `<polyline points="${l.map(pt).join(' ')}"/>`).join('')}${c.cornerRadius || hasEdgeCurves(c) ? `<polygon class="rounded-outline" points="${roundedContour(mesh).map(pt).join(' ')}"/>` : ''}${this.selection ? `<polygon class="selection-area" points="${this.selection.map(pt).join(' ')}"/>${this.selection.map((p, i) => handle(p, `class="selection-handle" data-selection-index="${i}"`)).join('')}` : ''}${controls.map((p, i) => (i === this.selectedPoint || this.tool === 'mesh' || (this.tool === 'corners' && [0, n - 1, n * n - 1, n * (n - 1)].includes(i)) ? handle(p, `data-index="${i}" class="${i === this.selectedPoint ? 'is-selected' : ''}"`) : '')).join('')}${this.pointLabel(controls[this.selectedPoint], box)}`;
  }
  pointLabel(p, box) {
    if (!p || this.selection) return '';
    return `<g class="cal-point-label" transform="translate(${p[0] * 1000} ${p[1] * 1000}) scale(${(1000 * VIEW_SPAN) / (box.width || 1000)} ${(1000 * VIEW_SPAN) / (box.height || 1000)})"><rect x="14" y="-13" width="165" height="26" rx="5"/><text x="22" y="4">X ${(p[0] * this.config.width).toFixed(1)} · Y ${(p[1] * this.config.height).toFixed(1)}</text></g>`;
  }
  async decode(file, limitBytes = true) {
    if (
      !file ||
      !['image/png', 'image/jpeg', 'image/webp'].includes(file.type) ||
      !file.size ||
      (limitBytes && file.size > 20 * 1024 * 1024)
    )
      throw Error(limitBytes ? '请选择 20 MB 内 PNG/JPG/WebP' : '请选择 PNG/JPG/WebP 图片');
    const url = URL.createObjectURL(file),
      image = new Image();
    try {
      image.src = url;
      await image.decode();
      if (
        image.naturalWidth * image.naturalHeight > 8_000_000 ||
        Math.max(image.naturalWidth, image.naturalHeight) > 4096 ||
        Math.min(image.naturalWidth, image.naturalHeight) < 64
      )
        throw Error('图片应为 64–4096 像素且最多 800 万像素');
      return image;
    } finally {
      URL.revokeObjectURL(url);
    }
  }
  async change(target) {
    if (!target.id?.startsWith('cal-')) return false;
    if (this.saving || this.loading) return true;
    if (target.id.startsWith('cal-side-')) {
      this.updateSideWidth(target, true);
      this.interaction = null;
      this.paint();
      return true;
    }
    this.loading = true;
    try {
      if (target.id === 'cal-background') {
        const file = target.files[0];
        if (!file) return true;
        const img = await this.decode(file);
        const category = this.category;
        this.reset();
        this.name = file.name.replace(/\.[^.]+$/, '');
        this.category = category;
        this.config = defaultCalibration(img.naturalWidth, img.naturalHeight);
        this.config.surface.edgeWidths = defaultSideWidths();
        this.config.blackMask.enabled = true;
        this.config.edgeRefine = defaultEdgeRefine(true);
        this.background = file;
        this.backgroundImage = img;
        this.assetRevision++;
        this.dirty = true;
      } else if (target.id === 'cal-design') {
        if (!target.files[0]) return true;
        const image = await this.decode(target.files[0], false);
        this.remember();
        this.design = image;
        this.previewPixels = null;
        this.assetRevision++;
      } else if (target.id === 'cal-mask') {
        const file = target.files[0];
        if (!file) return true;
        if (file.type !== 'image/png') throw Error('蒙版仅支持 PNG');
        const img = await this.decode(file);
        if (img.naturalWidth !== this.config.width || img.naturalHeight !== this.config.height)
          throw Error('蒙版尺寸必须与背景一致');
        this.remember();
        this.mask = file;
        this.maskImage = img;
        this.assetRevision++;
        this.dirty = true;
      } else if (target.id === 'cal-grid') {
        this.remember();
        const n = this.config.gridSize,
          p = this.config.points;
        this.config.points = gridFromCorners(
          [p[0], p[n - 1], p[n * n - 1], p[n * (n - 1)]],
          Number(target.value),
        );
        this.config.gridSize = Number(target.value);
        this.selectedPoint = 0;
      } else if (target.id === 'cal-region-name') {
        if (!target.value.trim() || target.value.length > 40) throw Error('区域名称需 1–40 个字符');
        this.remember();
        this.activeRegion.name = target.value.trim();
      } else if (target.id === 'cal-region-enabled') {
        if (
          !target.checked &&
          !this.regions.some((r) => r.id !== this.activeRegionId && r.enabled)
        ) {
          target.checked = true;
          throw Error('至少保留一个启用的渲染区域');
        }
        this.remember();
        this.activeRegion.enabled = target.checked;
      } else if (target.id.startsWith('cal-source-')) {
        const index = Number(target.id.slice('cal-source-'.length));
        if (![0, 1, 2, 3].includes(index)) return true;
        const sourceRect = [...this.config.sourceRect];
        sourceRect[index] = Number(target.value) / 100;
        validateCalibration({ ...this.config, sourceRect });
        this.remember();
        this.config.sourceRect = sourceRect;
      } else if (target.id === 'cal-material-view') {
        if (!['composite', 'lighting', 'coverage', 'original'].includes(target.value)) return true;
        this.materialView = target.value;
      } else if (
        ['cal-surface-enabled', 'cal-surface-source', 'cal-surface-edge'].includes(target.id)
      ) {
        const key = {
          'cal-surface-enabled': 'enabled',
          'cal-surface-source': 'source',
          'cal-surface-edge': 'lightEdge',
        }[target.id];
        const surface = {
          ...this.config.surface,
          [key]: key === 'enabled' ? target.checked : target.value,
        };
        validateCalibration({ ...this.config, surface });
        this.remember();
        this.config.surface = surface;
      } else if (target.id === 'cal-fabric-enabled') {
        this.remember();
        this.config.fabric.enabled = target.checked;
      } else if (target.id === 'cal-edge-refine-enabled') {
        validateCalibration({
          ...this.config,
          edgeRefine: { ...this.config.edgeRefine, enabled: target.checked },
        });
        this.remember();
        this.config.edgeRefine.enabled = target.checked;
      } else if (target.id === 'cal-zoom') {
        this.setZoom(Number(target.value));
        return true;
      } else if (target.id === 'cal-black-only') {
        this.remember();
        this.config.whiteMask.enabled = false;
        this.config.blackMask.enabled = target.checked;
        if (target.checked) this.config.greenMask.enabled = false;
      } else if (target.id === 'cal-green-only') {
        this.remember();
        this.config.whiteMask.enabled = false;
        this.config.greenMask.enabled = target.checked;
        if (target.checked) this.config.blackMask.enabled = false;
      } else if (target.id === 'cal-lock') {
        this.remember();
        this.lockRectangle = target.checked;
        if (this.lockRectangle) {
          if (this.selection)
            this.selection = cornersOf({
              ...this.config,
              points: rectangleGrid(
                gridFromCorners(this.selection, this.config.gridSize),
                this.config.gridSize,
              ),
            });
          else {
            this.config.points = rectangleGrid(this.config.points, this.config.gridSize);
          }
        }
      } else if (target.id === 'cal-snap') {
        this.snap = target.checked;
      } else if (target.id === 'cal-point') {
        this.selectedPoint = Number(target.value);
        this.drawOverlay();
        this.syncCoordinates();
        return true;
      } else if (target.id === 'cal-x' || target.id === 'cal-y') {
        this.updateCoordinate(target);
        this.interaction = null;
        return true;
      } else if (target.id === 'cal-name') {
        this.remember();
        this.name = target.value;
        this.dirty = true;
        return true;
      } else if (target.id === 'cal-category') {
        this.remember();
        this.category = target.value;
        this.dirty = true;
        return true;
      } else if (target.id === 'cal-watermark-text') {
        this.remember();
        this.config.watermark.text = target.value;
        this.dirty = true;
        this.paint();
        return true;
      } else return true;
      this.render();
    } catch (error) {
      this.flash(error.message);
    } finally {
      this.loading = false;
    }
    return true;
  }
  async handle(action, id) {
    if (!action.startsWith('cal:')) return false;
    if (this.saving || this.loading) return true;
    if (action === 'cal:panel') {
      this.openPanel(id, true);
      this.render();
      return true;
    }
    if (action === 'cal:fit-view') {
      this.fitView();
      return true;
    }
    if (action === 'cal:expand') {
      this.expanded = !this.expanded;
      this.root?.querySelector('.cal-editor')?.classList.toggle('is-expanded', this.expanded);
      const button = this.root?.querySelector('[data-action="cal:expand"]');
      if (button) {
        button.textContent = this.expanded ? '收起工作区' : '展开工作区';
        button.setAttribute('aria-pressed', String(this.expanded));
      }
      this.drawOverlay();
      return true;
    }
    if (action === 'cal:edge-refine-preset') {
      this.cancelInteraction();
      validateCalibration({
        ...this.config,
        edgeRefine: defaultEdgeRefine(true),
        edgeSoftness: 0.8,
      });
      this.remember();
      this.config.edgeRefine = defaultEdgeRefine(true);
      this.config.edgeSoftness = 0.8;
      this.materialView = 'composite';
      this.flash(
        '已启用局部边缘净化与曲线抗锯齿；四角、黑色阈值、图案比例不变。检查效果后保存并重新验收发布。',
      );
    } else if (action === 'cal:curves-reset') {
      this.remember();
      this.config.edgeCurves = defaultEdgeCurves();
    } else if (action === 'cal:fabric-preset') {
      this.cancelInteraction();
      this.remember();
      this.config.fabric = defaultFabric(true);
      this.config.blackMask = { enabled: true, maxBrightness: 110, maxChroma: 20, softness: 10 };
      this.config.greenMask.enabled = false;
      this.config.whiteMask.enabled = false;
      this.config.fold = 0;
      this.config.plush = 0;
      this.materialView = 'composite';
    } else if (action === 'cal:region-select') {
      this.selectRegion(id);
    } else if (action === 'cal:region-add' || action === 'cal:region-copy') {
      if (this.regions.length >= MAX_REGIONS) throw Error(`每张背景最多 ${MAX_REGIONS} 个区域`);
      this.cancelInteraction();
      this.remember();
      const config =
        action === 'cal:region-copy'
          ? structuredClone(this.config)
          : defaultCalibration(this.config.width, this.config.height);
      if (action === 'cal:region-add') {
        config.blackMask.enabled = true;
        config.edgeRefine = defaultEdgeRefine(true);
        config.surface.edgeWidths = defaultSideWidths();
      }
      let sequence = 1;
      while (this.regions.some((r) => r.id === `region-${sequence}`)) sequence++;
      const region = { id: `region-${sequence}`, name: `区域 ${sequence}`, enabled: true, config };
      this.regions.push(region);
      this.selectRegion(region.id);
      if (action === 'cal:region-add') this.tool = 'select';
      this.inspectorTab = 'shape';
    } else if (action === 'cal:region-delete') {
      if (this.regions.length === 1) throw Error('至少保留一个区域；移除整张背景请用 Del');
      if (!this.regions.some((r) => r.id !== this.activeRegionId && r.enabled))
        throw Error('请先启用其他区域再删除此区域');
      if (!confirm(`删除“${this.activeRegion.name}”？其他区域不受影响，可按 Ctrl+Z 恢复。`))
        return true;
      this.cancelInteraction();
      this.remember();
      const index = this.regions.indexOf(this.activeRegion);
      this.regions.splice(index, 1);
      this.selectRegion(this.regions[Math.min(index, this.regions.length - 1)].id);
    } else if (action === 'cal:region-back' || action === 'cal:region-forward') {
      this.cancelInteraction();
      const index = this.regions.indexOf(this.activeRegion),
        next = index + (action === 'cal:region-back' ? -1 : 1);
      if (next < 0 || next >= this.regions.length) return true;
      this.remember();
      [this.regions[index], this.regions[next]] = [this.regions[next], this.regions[index]];
    } else if (action === 'cal:source-reset') {
      this.remember();
      this.config.sourceRect = [0, 0, 1, 1];
    } else if (action === 'cal:tool') {
      if (!['select', 'corners', 'mesh', 'move'].includes(id)) return true;
      this.inspectorTab = 'shape';
      if (id === 'corners' && this.selection) return this.handle('cal:fill');
      this.tool = id;
      this.preview = false;
      if (id !== 'select') this.selection = null;
      if (id === 'mesh') this.lockRectangle = false;
    } else if (action === 'cal:fill') {
      if (
        !this.selection ||
        this.selection.some(
          (p, i) =>
            Math.hypot(
              p[0] - this.selection[(i + 1) % 4][0],
              p[1] - this.selection[(i + 1) % 4][1],
            ) < 0.006,
        )
      )
        throw Error('请先框选一个有效区域（每边至少为背景的 0.6%）');
      const points = gridFromCorners(this.selection, this.config.gridSize);
      validateCalibration({ ...this.config, points });
      this.remember();
      this.config.points = points;
      this.selection = null;
      this.lockRectangle = false;
      this.tool = 'corners';
      this.preview = false;
    } else if (action === 'cal:align') {
      this.remember();
      this.config.points = rectangleGrid(
        this.selection ? gridFromCorners(this.selection, this.config.gridSize) : this.config.points,
        this.config.gridSize,
      );
      this.selection = null;
      this.lockRectangle = true;
      this.tool = 'corners';
    } else if (action === 'cal:undo') {
      if (this.cancelInteraction()) return true;
      if (this.history.length) {
        this.restore(this.history.pop());
        // Undo is a new unsaved edit, even when the checkpoint predates a save.
        this.dirty = true;
      }
    } else if (action === 'cal:cancel') {
      if (this.cancelInteraction()) return true;
      this.selection = null;
      this.tool = 'corners';
      this.preview = false;
      this.error = '';
    } else if (action === 'cal:preview') this.preview = !this.preview;
    else if (action === 'cal:remove-mask') {
      if (!this.mask) return true;
      this.remember();
      this.mask = null;
      this.maskImage = null;
      this.assetRevision++;
      this.dirty = true;
    } else if (action === 'cal:publish') {
      if (this.saving) return true;
      if (!this.background) throw Error('先上传背景');
      validateCalibration(this.sceneConfig());
      if (!await this.confirmPublish())
        return true;
      this.saving = true;
      try {
        const previousId = this.id;
        const r = await this.service.saveCalibration(
          {
            id: this.id,
            name: this.name,
            category: this.category,
            config: this.sceneConfig(),
          },
          this.background,
          this.mask,
        );
        this.id = r.id;
        // Checkpoints for this background still belong to the saved record;
        // undoing a parameter after first save must not create a duplicate template.
        for (const checkpoint of this.history)
          if (checkpoint.id === previousId && checkpoint.background === this.background)
            checkpoint.id = r.id;
        this.reset();
        this.flash('背景已发布，可在模板组中使用此背景创建模板组。');
      } finally {
        this.saving = false;
      }
    }
    this.render();
    return true;
  }
}
