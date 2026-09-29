import { history } from '@umijs/max';
import { Button, Card, Checkbox, Empty, Input, message, Modal, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { createTemplateGroup, deleteCalibration, deleteTemplateGroup, listCalibrations, listTemplateGroups, setTemplateGroupStatus, updateTemplateGroup, type SaveTemplateGroup, type StoreCalibration, type StoreId, type StoreTemplateGroup } from '@/api/store';
import { ApiError, CategorySelect, PageToolbar, StorePage, useStoreCategories } from '../common';
import StoreImage from '../StoreImage';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';
import { userErrorMessage } from '@/api/request';
import { loadCalibrationFiles, rememberCalibrationFiles } from '../calibration/files';

type Scene = { id?: StoreId; name: string; categoryId: StoreId; ossKey: string; imageUrl?: string; width: number; height: number; config: string; maskOssKey?: string; templateConfigJson?: string };
type Editor = { group?: StoreTemplateGroup; name: string; categoryId?: StoreId; scenes: Scene[] };
const same = (a?: StoreId, b?: StoreId) => String(a) === String(b);
const fromCalibration = (item: StoreCalibration): Scene => {
  const { width, height } = JSON.parse(item.calibrationConfigJson) as { width: number; height: number };
  return { id: item.id, name: item.name, categoryId: item.categoryId, ossKey: item.backgroundOssKey, imageUrl: item.backgroundUrl, width, height, config: item.calibrationConfigJson, maskOssKey: item.maskOssKey };
};

export default function TemplateGroupPage() {
  const userInfo = useUserStore(state => state.userInfo);
  const canAdd = hasPermi(userInfo, ['store:template-group:add']);
  const canEdit = hasPermi(userInfo, ['store:template-group:edit']);
  const canRemove = hasPermi(userInfo, ['store:template-group:remove']);
  const canEditCalibration = hasPermi(userInfo, ['store:calibration:edit']);
  const canRemoveCalibration = hasPermi(userInfo, ['store:calibration:remove']);
  const categories = useStoreCategories();
  const [groups, setGroups] = useState<StoreTemplateGroup[]>([]);
  const [backgrounds, setBackgrounds] = useState<StoreCalibration[]>([]);
  const [categoryId, setCategoryId] = useState<StoreId>();
  const [editor, setEditor] = useState<Editor>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [openingId, setOpeningId] = useState<string>();
  const [error, setError] = useState<unknown>();
  const openedInitial = useRef(false);
  const opening = useRef<AbortController | undefined>(undefined);
  const load = async () => {
    setLoading(true);
    try {
      const [calibrations, savedGroups] = await Promise.all([listCalibrations(), listTemplateGroups()]);
      setBackgrounds((calibrations.data || []).filter(item => item.status === 'PUBLISHED'));
      setGroups(savedGroups.data || []);
      setError(undefined);
    } catch (failure) { setError(failure); }
    finally { setLoading(false); }
  };
  const editGroup = (group: StoreTemplateGroup) => setEditor({ group, name: group.name, categoryId: group.categoryId, scenes: group.templates.map(template => {
    const published = backgrounds.find(item => item.backgroundOssKey === template.ossKey);
    return published ? fromCalibration(published) : { name: template.name, categoryId: group.categoryId, ossKey: template.ossKey, imageUrl: template.imageUrl,
      width: template.width, height: template.height, config: group.calibrationConfigJson, maskOssKey: group.maskOssKey, templateConfigJson: template.templateConfigJson };
  }) });
  useEffect(() => { void load(); return () => opening.current?.abort(); }, []);
  const openCalibration = async (item: StoreCalibration) => {
    if (opening.current) return;
    const controller = new AbortController();
    opening.current = controller;
    setOpeningId(String(item.id));
    try {
      const files = await loadCalibrationFiles(item, controller.signal);
      if (controller.signal.aborted) return;
      rememberCalibrationFiles(item, files);
      history.push(`/store-management/calibration?calibrationId=${item.id}`);
    } catch (failure) {
      if (!controller.signal.aborted) message.error(userErrorMessage(failure));
    } finally {
      if (opening.current === controller) { opening.current = undefined; setOpeningId(undefined); }
    }
  };
  useEffect(() => {
    if (loading || openedInitial.current) return;
    openedInitial.current = true;
    const groupId = new URLSearchParams(location.search).get('groupId');
    const group = groups.find(item => String(item.id) === groupId);
    if (group) editGroup(group);
  }, [loading]);
  const selected = editor?.scenes || [];
  const visibleBackgrounds = backgrounds.filter(item => categoryId == null || same(item.categoryId, categoryId));
  const visibleGroups = groups.filter(item => categoryId == null || same(item.categoryId, categoryId));
  const save = async () => {
    if (!editor) return;
    if (!editor.name.trim() || editor.name.trim().length > 80) { message.warning('模板组名称需 1–80 个字符'); return; }
    if (editor.categoryId == null || !selected.length || selected.length > 8) { message.warning('请选择同类目的 1–8 张背景'); return; }
    setSaving(true);
    try {
      const payload: SaveTemplateGroup = { name: editor.name.trim(), categoryId: editor.categoryId,
        calibrationConfigJson: selected[0].config, maskOssKey: selected[0].maskOssKey,
        templates: selected.map(scene => ({ name: scene.name, ossKey: scene.ossKey, imageUrl: scene.imageUrl,
          width: scene.width, height: scene.height,
          templateConfigJson: scene.id == null ? (scene.templateConfigJson || '{}') : JSON.stringify({ calibrationId: scene.id }) })) };
      if (editor.group) await updateTemplateGroup(editor.group.id, payload); else await createTemplateGroup(payload);
      setEditor(undefined); message.success('模板组已保存'); await load();
    } catch (failure) { message.error(userErrorMessage(failure)); }
    finally { setSaving(false); }
  };
  const remove = async (group: StoreTemplateGroup) => {
    try {
      await deleteTemplateGroup(group.id);
      message.success('已删除，历史任务保留'); await load();
    } catch (failure) { message.error(userErrorMessage(failure)); }
  };
  const removeBackground = async (item: StoreCalibration) => {
    try { await deleteCalibration(item.id); message.success('背景已删除，模板组已同步'); await load(); }
    catch (failure) { message.error(userErrorMessage(failure)); }
  };
  const enable = async (group: StoreTemplateGroup) => {
    try { await setTemplateGroupStatus(group.id, 'ACTIVE'); message.success('已启用'); await load(); }
    catch (failure) { message.error(userErrorMessage(failure)); }
  };
  const move = (index: number, direction: number) => setEditor(current => {
    if (!current) return current;
    const scenes = [...current.scenes]; [scenes[index], scenes[index + direction]] = [scenes[index + direction], scenes[index]];
    return { ...current, scenes };
  });
  return <StorePage title="模板组">
    <PageToolbar><CategorySelect categories={categories} value={categoryId} onChange={setCategoryId} />
      {canAdd && <Button type="primary" onClick={() => setEditor({ name: '', categoryId: categoryId || categories[0]?.id, scenes: [] })}>新增模板组</Button>}</PageToolbar>
    <ApiError error={error} />
    <Card title={`已发布背景 · ${visibleBackgrounds.length} 张`} style={{ marginBottom: 16 }}>
      <Typography.Paragraph type="secondary">验收发布后的背景可组成模板组；已有组可通过编辑添加背景。</Typography.Paragraph>
      {visibleBackgrounds.length ? <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(190px,1fr))', gap: 12 }}>
        {visibleBackgrounds.map(item => <Card key={String(item.id)} size="small" cover={<StoreImage url={item.backgroundUrl} alt={item.name} style={{ width: '100%', aspectRatio: '4 / 3', objectFit: 'cover' }} />}>
          <Typography.Text strong>{item.name}</Typography.Text><br />
          <Typography.Text type="secondary">{categories.find(c => same(c.id, item.categoryId))?.name || '商品类目'} · 已加入 {groups.filter(g => g.status !== 'DELETED' && g.templates.some(t => t.ossKey === item.backgroundOssKey)).length} 个组</Typography.Text><br />
          <Space size="small" style={{ marginTop: 8 }}>
            {canEditCalibration && <Button size="small" loading={openingId === String(item.id)} disabled={openingId != null && openingId !== String(item.id)} onClick={() => void openCalibration(item)}>编辑</Button>}
            {canRemoveCalibration && <Popconfirm title="删除这张背景？" description="会同步从模板组移除，历史渲染结果保留。" onConfirm={() => void removeBackground(item)}><Button size="small" danger>删除</Button></Popconfirm>}
          </Space>
        </Card>)}
      </div> : <Empty description="此类目暂无已发布背景" />}
    </Card>
    <Card title={`套图模板组 · ${visibleGroups.length} 组`}>
      <Typography.Paragraph type="secondary">每组选择 1–8 张同类目背景，第一张为主图；删除不会影响历史任务。</Typography.Paragraph>
      {visibleGroups.length ? <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(310px,1fr))', gap: 16 }}>
        {visibleGroups.map(group => <Card key={String(group.id)} size="small" title={group.name} extra={<Tag color={group.status === 'ACTIVE' ? 'success' : 'warning'}>{group.status === 'ACTIVE' ? '已启用' : '已停用'}</Tag>}>
          <Typography.Text type="secondary">{categories.find(c => same(c.id, group.categoryId))?.name || '商品类目'} · {group.templates.length} 张背景</Typography.Text>
          <div style={{ display: 'flex', gap: 8, overflowX: 'auto', margin: '12px 0' }}>{group.templates.map((template, index) => {
            const published = backgrounds.find(item => item.backgroundOssKey === template.ossKey);
            return <div key={String(template.id)} style={{ flex: '0 0 88px' }}><StoreImage url={template.imageUrl} alt={template.name} style={{ width: 88, height: 88, objectFit: 'cover', borderRadius: 4 }} /><Typography.Text type="secondary" style={{ fontSize: 11 }}>{index === 0 ? '主图' : `附图${index}`}</Typography.Text>
              {published && <div>{canEditCalibration && <Button type="link" size="small" style={{ padding: 0 }} loading={openingId === String(published.id)} disabled={openingId != null && openingId !== String(published.id)} onClick={() => void openCalibration(published)}>编辑</Button>}{canRemoveCalibration && <Popconfirm title="删除这张背景？" description="会同步从所有模板组移除，历史渲染结果保留。" onConfirm={() => void removeBackground(published)}><Button type="link" size="small" danger style={{ padding: 0 }}>删除</Button></Popconfirm>}</div>}
            </div>;
          })}</div>
          <Space wrap>
            {group.status === 'ACTIVE' && <Button type="link" onClick={() => history.push(`/image/library?storeGroupId=${group.id}&storeCategoryId=${group.categoryId}`)}>使用此组</Button>}
            {canEdit && <Button onClick={() => editGroup(group)}>编辑</Button>}
            {canEdit && group.status === 'INACTIVE' && <Button onClick={() => void enable(group)}>启用</Button>}
            {canRemove && <Popconfirm title="删除模板组？" description="删除后无法在此页面恢复；历史任务与背景文件保留。" onConfirm={() => void remove(group)}><Button danger>删除</Button></Popconfirm>}
          </Space>
        </Card>)}
      </div> : <Empty description="此类目暂无模板组" />}
    </Card>
    <Modal title={editor?.group ? '编辑模板组' : '新增模板组'} open={!!editor} width={760} okText="保存配置" okButtonProps={{ loading: saving }} cancelButtonProps={{ disabled: saving }} onOk={() => void save()} onCancel={() => { if (!saving) setEditor(undefined); }} destroyOnHidden>
      {editor && <Space direction="vertical" style={{ width: '100%' }} size="middle">
        <label>模板组名称<Input maxLength={80} value={editor.name} onChange={event => setEditor({ ...editor, name: event.target.value })} placeholder="例如：地毯夏季上新组" /></label>
        <label>商品类目<Select style={{ width: '100%' }} value={editor.categoryId} options={categories.map(item => ({ value: item.id, label: item.name }))} onChange={value => setEditor({ ...editor, categoryId: value, scenes: [] })} /></label>
        <div><Typography.Text strong>选择背景（{selected.length}/8）</Typography.Text><div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(125px,1fr))', gap: 10, maxHeight: 250, overflowY: 'auto', marginTop: 10 }}>
          {backgrounds.filter(item => same(item.categoryId, editor.categoryId)).map(item => { const checked = selected.some(scene => scene.ossKey === item.backgroundOssKey); return <Card key={String(item.id)} size="small" styles={{ body: { padding: 8 } }}>
            <StoreImage url={item.backgroundUrl} alt={item.name} style={{ width: '100%', aspectRatio: '1', objectFit: 'cover' }} /><Checkbox checked={checked} onChange={() => {
            if (!checked && selected.length >= 8) { message.warning('每组最多 8 张背景'); return; }
            setEditor({ ...editor, scenes: checked ? selected.filter(scene => scene.ossKey !== item.backgroundOssKey) : [...selected, fromCalibration(item)] });
          }}>{item.name}</Checkbox>
          </Card>; })}
        </div></div>
        <div><Typography.Text strong>输出顺序</Typography.Text><Typography.Paragraph type="secondary">第 1 张为主图，其余为附图。</Typography.Paragraph>
          {selected.map((scene, index) => <div key={scene.ossKey} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 0', borderBottom: '1px solid var(--app-border-react)' }}>
            <span style={{ flex: 1 }}>{index + 1}. {scene.name} {index === 0 && <Tag color="blue">主图</Tag>}</span>
            <Button size="small" disabled={index === 0} onClick={() => move(index, -1)}>上移</Button><Button size="small" disabled={index === selected.length - 1} onClick={() => move(index, 1)}>下移</Button>
            <Button size="small" danger onClick={() => setEditor({ ...editor, scenes: selected.filter(item => item.ossKey !== scene.ossKey) })}>移除</Button>
          </div>)}
        </div>
      </Space>}
    </Modal>
    {loading && <div>加载中…</div>}
  </StorePage>;
}
