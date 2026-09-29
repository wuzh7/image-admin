import { renderDeclarative } from '../engine/declarative.mjs';

let assets, revision;
self.onmessage = async ({ data }) => {
  try {
    if (!assets || revision !== data.assetRevision) {
      assets?.background.close();
      assets?.mask?.close();
      assets = null;
      const background = await createImageBitmap(data.background);
      try {
        assets = {
          background,
          mask: data.mask ? await createImageBitmap(data.mask) : null,
          design: data.design,
        };
      } catch (error) {
        background.close();
        throw error;
      }
      revision = data.assetRevision;
    }
    const c = data.config,
      scale = data.fast ? Math.min(1, 512 / Math.max(c.width, c.height)) : 1;
    const canvas = new OffscreenCanvas(
      Math.max(1, Math.round(c.width * scale)),
      Math.max(1, Math.round(c.height * scale)),
    );
    const result = renderDeclarative(
      canvas.getContext('2d', { willReadFrequently: true }),
      assets.background,
      assets.design,
      c,
      assets.mask,
      {
        scale,
        view: data.view || 'composite',
      },
    );
    const bitmap = canvas.transferToImageBitmap();
    self.postMessage({ id: data.id, bitmap, fast: data.fast, warnings: result.warnings }, [bitmap]);
  } catch (error) {
    self.postMessage({ id: data.id, error: error.message }, []);
  }
};
