import { POINT_MIN, POINT_MAX } from '../engine/schema.mjs';

// Leave room beyond the permitted points, so edge handles remain fully reachable.
// These are view-only values, never saved in the template or added to output pixels.
export const VIEW_MIN = POINT_MIN - 0.05;
export const VIEW_MAX = POINT_MAX + 0.05;
export const VIEW_SPAN = VIEW_MAX - VIEW_MIN;
export const VIEW_BOX = `${VIEW_MIN * 1000} ${VIEW_MIN * 1000} ${VIEW_SPAN * 1000} ${VIEW_SPAN * 1000}`;
export const PAPER_OFFSET = (-VIEW_MIN / VIEW_SPAN) * 100;
export const PAPER_SIZE = 100 / VIEW_SPAN;

// Keep the actual painted canvas, scroll offsets, open panels and editing focus.
// A shell update must not replace it with an empty canvas while a worker is busy.
export function replaceCalibrationView(root, html) {
  const viewport = root.querySelector('.calibration-viewport');
  if (!viewport) {
    root.innerHTML = html;
    return;
  }
  const scroll = [viewport.scrollLeft, viewport.scrollTop];
  const doc = root.ownerDocument,
    active = doc.activeElement;
  const focus = root.contains(active) && active.id ? { id: active.id } : null;
  const detailsSelector = '.calibration-controls details, [data-cal-detail]';
  const detailKey = (el) =>
    el.dataset.calDetail || el.id || el.querySelector('summary')?.textContent;
  const open = new Map(
    [...root.querySelectorAll(detailsSelector)].map((el) => [detailKey(el), el.open]),
  );
  const containers = ['main', '.sidebar', '.calibration-controls'].map((selector) => {
    const el = root.querySelector(selector);
    return [selector, el?.scrollLeft || 0, el?.scrollTop || 0];
  });
  const pageScroll = [doc.documentElement.scrollLeft, doc.documentElement.scrollTop];
  const panelScroll = [...root.querySelectorAll('.cal-inspector-panel')].map((el) => [
    el.id,
    el.scrollTop,
  ]);
  root.innerHTML = html;
  const replacement = root.querySelector('.calibration-viewport');
  if (!replacement) return;
  viewport.querySelector('.calibration-stage').style.cssText =
    replacement.querySelector('.calibration-stage').style.cssText;
  replacement.replaceWith(viewport);
  for (const el of root.querySelectorAll(detailsSelector)) {
    const key = detailKey(el);
    if (open.has(key)) el.open = open.get(key);
  }
  if (focus) {
    const el = doc.getElementById(focus.id);
    if (el && !el.closest('[hidden]')) el.focus({ preventScroll: true });
  }
  viewport.scrollLeft = scroll[0];
  viewport.scrollTop = scroll[1];
  for (const [selector, x, y] of containers) {
    const el = root.querySelector(selector);
    if (el) {
      el.scrollLeft = x;
      el.scrollTop = y;
    }
  }
  doc.documentElement.scrollLeft = pageScroll[0];
  doc.documentElement.scrollTop = pageScroll[1];
  for (const [id, top] of panelScroll) {
    const panel = doc.getElementById(id);
    if (panel) panel.scrollTop = top;
  }
}

export function zoomFromWheel(zoom, delta, mode = 0) {
  const pixels = delta * (mode === 1 ? 16 : mode === 2 ? 240 : 1);
  return Math.max(
    10,
    Math.min(300, Math.round(zoom * Math.exp(-Math.max(-240, Math.min(240, pixels)) * 0.002))),
  );
}

export function pointerPosition(ev, box) {
  const imageWidth = box.width / VIEW_SPAN,
    imageHeight = box.height / VIEW_SPAN;
  return [
    (ev.clientX - (box.left - VIEW_MIN * imageWidth)) / imageWidth,
    (ev.clientY - (box.top - VIEW_MIN * imageHeight)) / imageHeight,
  ].map((v) => Math.max(POINT_MIN, Math.min(POINT_MAX, v)));
}

export function snapTolerance(box) {
  return [(8 * VIEW_SPAN) / box.width, (8 * VIEW_SPAN) / box.height];
}

export function translatePoints(points, delta) {
  const shift = [0, 1].map((axis) => {
    const values = points.map((p) => p[axis]);
    return Math.max(
      POINT_MIN - Math.min(...values),
      Math.min(POINT_MAX - Math.max(...values), delta[axis]),
    );
  });
  return points.map((p) => p.map((v, axis) => v + shift[axis]));
}
