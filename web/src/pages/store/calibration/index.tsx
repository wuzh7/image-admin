import { message, Modal, theme } from 'antd';
import { useEffect, useRef, type CSSProperties } from 'react';
import { getImageCategoryOptions } from '@/api/image';
import {
  listCalibrations, saveCalibration, uploadTemplateImage,
  type StoreId,
} from '@/api/store';
import { StorePage } from '../common';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';
import { userErrorMessage } from '@/api/request';
import { loadCalibrationFiles, takeCalibrationFiles } from './files';
// @ts-ignore The demo editor is shared JavaScript and has no TypeScript declarations.
import { CalibrationManager } from './editor/manager.mjs';
import './editor/styles.css';

type EditorRecord = {
  id: string; backgroundKey: string; maskKey?: string;
};

export default function CalibrationPage() {
  const userInfo = useUserStore(state => state.userInfo);
  const canAdd = hasPermi(userInfo, ['store:calibration:add']);
  const canEdit = hasPermi(userInfo, ['store:calibration:edit']);
  const rootRef = useRef<HTMLDivElement>(null);
  const { token } = theme.useToken();
  const [modal, contextHolder] = Modal.useModal();

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;
    let mounted = true;
    let frame = 0;
    let editor: any;
    const fileCache = new Map<string, Blob>();
    let currentRecord: EditorRecord | undefined;
    const upload = async (blob: Blob, previousKey?: string, filename = 'background.png') => {
      if (previousKey && fileCache.get(previousKey) === blob) return previousKey;
      const source = blob instanceof File ? blob : new File([blob], filename, { type: blob.type });
      const saved = (await uploadTemplateImage(source)).data;
      fileCache.set(saved.ossKey, blob);
      return saved.ossKey;
    };
    const service = {
      async saveCalibration(input: { id?: string; name: string; category: string; config: unknown }, background: Blob, mask?: Blob) {
        if (input.id ? !canEdit : !canAdd) throw new Error('没有保存背景模板的权限');
        const previous = currentRecord?.id === input.id ? currentRecord : undefined;
        const backgroundOssKey = await upload(background, previous?.backgroundKey,
          background instanceof File ? background.name : `background.${background.type.split('/')[1] || 'png'}`);
        const maskOssKey = mask ? await upload(mask, previous?.maskKey, 'mask.png') : undefined;
        const saved = (await saveCalibration({
          name: input.name, categoryId: input.category,
          calibrationConfigJson: JSON.stringify(input.config), backgroundOssKey, maskOssKey,
        }, input.id as StoreId | undefined)).data;
        currentRecord = { id: String(saved.id), backgroundKey: saved.backgroundOssKey, maskKey: saved.maskOssKey };
        return currentRecord;
      },
    };
    const render = () => {
      if (!mounted || !editor) return;
      if (editor.interaction) { frame = requestAnimationFrame(render); return; }
      editor.replaceView(root, editor.view());
      if (editor.id ? !canEdit : !canAdd) root.querySelector('[data-action="cal:publish"]')?.remove();
      editor.attach(root);
    };
    const queueRender = () => { cancelAnimationFrame(frame); frame = requestAnimationFrame(render); };
    const click = (event: MouseEvent) => {
      const button = (event.target as Element).closest<HTMLElement>('[data-action]');
      if (!button || button.hasAttribute('disabled')) return;
      if (button.dataset.action === 'cal:publish' && (editor?.id ? !canEdit : !canAdd)) return;
      void editor?.handle(button.dataset.action, button.dataset.id).catch((error: Error) => message.error(userErrorMessage(error)));
    };
    const change = (event: Event) => {
      const target = event.target as HTMLInputElement;
      if (target.dataset.calParam || !target.id?.startsWith('cal-')) return;
      void editor?.change(target).catch((error: Error) => message.error(userErrorMessage(error)));
    };
    root.addEventListener('click', click);
    root.addEventListener('change', change);
    void getImageCategoryOptions().then(async categories => {
      if (!mounted) return;
      editor = new CalibrationManager(service, {
        render: queueRender, flash: (text: string) => message.info(text),
        confirmPublish: () => new Promise<boolean>(resolve => modal.confirm({
          title: '验收并发布背景？',
          content: '请确认已用测试图检查黑色识别范围、家具保护、完整封边和水印。发布后可在模板组中使用此背景。',
          okText: '确认发布', cancelText: '返回检查',
          onOk: () => resolve(true), onCancel: () => resolve(false),
        })),
      });
      editor.categories = categories.data || [];
      const calibrationId = new URLSearchParams(window.location.search).get('calibrationId');
      if (calibrationId) {
        const record = (await listCalibrations()).data?.find(item => String(item.id) === calibrationId && item.status === 'PUBLISHED');
        if (!record) throw new Error('已发布背景不存在');
        const { background, mask } = takeCalibrationFiles(record) || await loadCalibrationFiles(record);
        await editor.loadPublished(record, background, mask);
        currentRecord = { id: String(record.id), backgroundKey: record.backgroundOssKey, maskKey: record.maskOssKey };
        fileCache.set(record.backgroundOssKey, background);
        if (record.maskOssKey && mask) fileCache.set(record.maskOssKey, mask);
      } else editor.reset();
      render();
    }).catch(error => { if (mounted) { root.textContent = '背景模板加载失败，请返回模板组重试'; message.error(userErrorMessage(error)); } });
    return () => {
      mounted = false; cancelAnimationFrame(frame);
      root.removeEventListener('click', click); root.removeEventListener('change', change);
      editor?.stopPreview();
      editor?.keyDocument?.removeEventListener('keydown', editor.keyHandler);
    };
  }, [modal, canAdd, canEdit]);

  return <StorePage title="背景模板校准">{contextHolder}<div className="store-calibration-editor" ref={rootRef} style={{ '--cal-primary': token.colorPrimary } as CSSProperties}>正在加载背景模板校准…</div></StorePage>;
}
