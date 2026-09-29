import { getStoreFile, type StoreCalibration } from '@/api/store';

type Files = { background: File; mask?: File };
let prepared: { id: string; backgroundKey: string; maskKey?: string; files: Files } | undefined;

export async function loadCalibrationFiles(record: StoreCalibration, signal?: AbortSignal): Promise<Files> {
  const load = async (key: string, url: string) => {
    const extension = key.split('.').pop() || 'png';
    // 列表 <img> 的缓存响应可能没有 CORS 头，编辑时需重新读取。
    const blob = url.startsWith('https://')
      ? await fetch(url, { signal, cache: 'reload' }).then(response => {
          if (!response.ok) throw new Error('背景模板加载失败');
          return response.blob();
        })
      : await getStoreFile(key, signal);
    return new File([blob], `background.${extension}`, { type: `image/${extension === 'jpg' ? 'jpeg' : extension}` });
  };
  const [background, mask] = await Promise.all([
    load(record.backgroundOssKey, record.backgroundUrl),
    record.maskOssKey ? load(record.maskOssKey, record.maskUrl || '') : Promise.resolve(undefined),
  ]);
  return { background, mask };
}

export function rememberCalibrationFiles(record: StoreCalibration, files: Files) {
  prepared = { id: String(record.id), backgroundKey: record.backgroundOssKey, maskKey: record.maskOssKey, files };
}

export function takeCalibrationFiles(record: StoreCalibration): Files | undefined {
  const saved = prepared;
  prepared = undefined;
  return saved?.id === String(record.id) && saved.backgroundKey === record.backgroundOssKey && saved.maskKey === record.maskOssKey
    ? saved.files : undefined;
}
