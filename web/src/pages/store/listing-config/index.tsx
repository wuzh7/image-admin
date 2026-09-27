import { Button, Card, Input, InputNumber, message, Modal, Popconfirm, Select, Space, Table, Typography, Upload } from 'antd';
import { useEffect, useState } from 'react';
import {
  deleteListingTemplate, listListingTemplates, listShops,
  saveListingProfile, uploadListingTemplate, type StoreId, type StoreListingTemplate, type StoreShop,
} from '@/api/store';
import { StorePage, useStoreCategories } from '../common';

const fields = [
  ['required', '平台强制词'], ['material', '产品材质词'], ['hot', '平台热词'],
  ['holiday', '节假日（可不选）'], ['scene', '场景词'],
] as const;
type PhraseField = typeof fields[number][0];
type Profile = {
  titleLanguage: 'en'; keywordMode: 'image'; words: Record<PhraseField, { en: string }[]>;
  selected: Record<PhraseField, number>; previewIndex: number; skuStart: number;
  skuDigits: number; skuSeparator: string; detailSeparator: string; carouselScope: 'spu' | 'both';
};
type ShopMeta = { categoryIds?: StoreId[] };
const defaultProfile = (): Profile => ({
  titleLanguage: 'en', keywordMode: 'image',
  words: {
    required: [{ en: '2D Flat' }], material: [{ en: 'Canvas Wall Art' }],
    hot: [{ en: 'Decorative Print' }, { en: 'Home Decoration' }],
    holiday: [{ en: 'Christmas' }, { en: 'Halloween' }],
    scene: [{ en: 'for Living Room' }, { en: 'for Bedroom' }],
  },
  selected: { required: 0, material: 0, hot: 0, holiday: -1, scene: 0 },
  previewIndex: 1, skuStart: 1, skuDigits: 2, skuSeparator: '', detailSeparator: '｜', carouselScope: 'both',
});

export default function ListingConfigPage() {
  const categories = useStoreCategories();
  const [shops, setShops] = useState<StoreShop[]>([]);
  const [shopId, setShopId] = useState<StoreId>();
  const [categoryId, setCategoryId] = useState<StoreId>();
  const [templates, setTemplates] = useState<StoreListingTemplate[]>([]);
  const [selectedTemplateId, setSelectedTemplateId] = useState<StoreId>();
  const [pendingFile, setPendingFile] = useState<File>();
  const [profile, setProfile] = useState<Profile>(defaultProfile);
  const [wordTexts, setWordTexts] = useState<Record<PhraseField, string>>({ required: '', material: '', hot: '', holiday: '', scene: '' });
  const [configOpen, setConfigOpen] = useState(false);
  const [savingProfile, setSavingProfile] = useState(false);

  const shopMeta = shops.find(item => String(item.id) === String(shopId))?.configJson;
  let linkedCategoryIds: StoreId[] = [];
  try { linkedCategoryIds = (JSON.parse(shopMeta || '{}') as ShopMeta).categoryIds || []; } catch { linkedCategoryIds = []; }
  const linkedCategories = categories.filter(item => linkedCategoryIds.some(id => String(id) === String(item.id)));
  const selectedTemplate = templates.find(item => String(item.id) === String(selectedTemplateId));

  useEffect(() => { void listShops().then(response => { setShops(response.data || []); setShopId(response.data?.[0]?.id); }); }, []);
  useEffect(() => {
    if (shopId == null || categoryId == null) return;
    let active = true;
    void listListingTemplates({ shopId, categoryId }).then(response => {
      if (!active) return;
      setTemplates(response.data || []);
      setSelectedTemplateId(undefined);
    });
    return () => { active = false; };
  }, [shopId, categoryId]);

  const openConfig = () => {
    if (!pendingFile && !selectedTemplate) return;
    let current = defaultProfile();
    try { if (selectedTemplate && !pendingFile) {
      const saved = JSON.parse(selectedTemplate.configJson || '{}') as Partial<Profile>;
      current = { ...current, ...saved, words: { ...current.words, ...saved.words }, selected: { ...current.selected, ...saved.selected } };
    } } catch { /* 保留默认值 */ }
    setProfile(current);
    setWordTexts(Object.fromEntries(fields.map(([field]) => [field, (current.words[field] || []).map(item => item.en).join('\n')])) as Record<PhraseField, string>);
    setConfigOpen(true);
  };
  const saveProfile = async () => {
    if ((!pendingFile && selectedTemplateId == null) || !configOpen) return;
    const words = Object.fromEntries(fields.map(([field]) => [field, wordTexts[field].split(/\r?\n/).map(en => en.trim()).filter(Boolean).map(en => ({ en }))])) as Profile['words'];
    const selected = { ...profile.selected };
    for (const [field, label] of fields) {
      if (!words[field].length || words[field].length > 20 || words[field].some(item => item.en.length > 160)) { message.error(`${label}请配置 1–20 个英文词条，每项不超过 160 字`); return; }
      if (selected[field] >= words[field].length) selected[field] = field === 'holiday' ? -1 : 0;
    }
    if (![profile.previewIndex, profile.skuStart, profile.skuDigits].every(Number.isInteger) || profile.previewIndex < 1 || profile.previewIndex > 10 || profile.skuStart < 1 || profile.skuStart > 999999 || profile.skuDigits < 1 || profile.skuDigits > 6) { message.error('请填写有效的图片位置与 SKU 编号'); return; }
    const nextProfile = { ...profile, words, selected };
    setSavingProfile(true);
    try {
      const saved = pendingFile
        ? (await uploadListingTemplate({ shopId: shopId!, categoryId: categoryId!, file: pendingFile, configJson: JSON.stringify(nextProfile) })).data
        : (await saveListingProfile(selectedTemplateId!, JSON.stringify(nextProfile))).data;
      setTemplates(current => pendingFile ? [saved, ...current] : current.map(item => String(item.id) === String(saved.id) ? saved : item));
      setSelectedTemplateId(saved.id); setPendingFile(undefined);
      setProfile(nextProfile); setConfigOpen(false);
      message.success('当前表格的生成配置已保存');
    }
    catch (error) { message.error(error instanceof Error ? error.message : '保存生成配置失败'); }
    finally { setSavingProfile(false); }
  };

  return <StorePage title="上架表格配置">
    <Card title="上架模板准备">
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(160px,1fr))', gap: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }}><Typography.Text strong>1. 选择店铺</Typography.Text>
          <Select placeholder="选择店铺" value={shopId} options={shops.map(shop => ({ value: shop.id, label: shop.name }))} onChange={value => { setShopId(value); setCategoryId(undefined); setTemplates([]); setSelectedTemplateId(undefined); setPendingFile(undefined); setConfigOpen(false); }} style={{ width: '100%' }} />
        </Space>
        <Space direction="vertical" style={{ width: '100%' }}><Typography.Text strong>2. 选择类目</Typography.Text>
          <Select placeholder="选择类目" value={categoryId} options={linkedCategories.map(item => ({ value: item.id, label: item.name }))} onChange={value => { setCategoryId(value); setTemplates([]); setSelectedTemplateId(undefined); setPendingFile(undefined); setConfigOpen(false); }} style={{ width: '100%' }} />
        </Space>
        <Space direction="vertical"><Typography.Text strong>3. 上架模板表格</Typography.Text>
          <Upload beforeUpload={file => {
            if (shopId == null || categoryId == null) { message.warning('请先选择店铺和类目'); return false; }
            setPendingFile(file); setSelectedTemplateId(undefined); setConfigOpen(false);
            return false;
          }} showUploadList={false} accept=".xlsx"><Button disabled={shopId == null || categoryId == null}>上传表格</Button></Upload>
          {pendingFile && <Typography.Text type="secondary">待配置：{pendingFile.name}</Typography.Text>}
        </Space>
        <Space direction="vertical"><Typography.Text strong>4. 生成配置</Typography.Text>
          <Button disabled={!pendingFile && !selectedTemplate} onClick={openConfig}>生成配置 / 标题词库</Button>
        </Space>
      </div>
    </Card>
    <Card title="已上架模板表格" style={{ marginTop: 16 }}>
      <Typography.Text type="secondary">上传表格后在第 4 步保存配置，表格才会显示在这里。选择已有表格可修改其独立配置。</Typography.Text>
      <Table rowKey="id" dataSource={templates} pagination={false} rowSelection={{ type: 'radio', selectedRowKeys: selectedTemplateId == null ? [] : [selectedTemplateId], onChange: keys => { setSelectedTemplateId(keys[0] as StoreId); setPendingFile(undefined); setConfigOpen(false); } }} columns={[
        { title: '唯一编号 / 原文件', key: 'file', render: (_, row) => <><strong>{row.templateCode}</strong>{!row.configJson && <Typography.Text type="warning"> · 未保存生成配置</Typography.Text>}<br /><Typography.Text type="secondary">{row.originalFilename}</Typography.Text></> },
        { title: '维护', key: 'actions', render: (_, row) => <Space>
          <Popconfirm title="删除上架表格模板？" description="已生成的历史文件保留。" onConfirm={async () => {
          await deleteListingTemplate(row.id); setTemplates(current => current.filter(item => item.id !== row.id));
          if (String(selectedTemplateId) === String(row.id)) { setSelectedTemplateId(undefined); setConfigOpen(false); }
        }}><Button size="small" danger>删除</Button></Popconfirm>
        </Space> },
      ]} locale={{ emptyText: '暂无表格模板' }} />
    </Card>
    <Modal open={configOpen} width={900} title={<>
      <div>上架生成配置 · {shops.find(item => String(item.id) === String(shopId))?.name}</div>
      <Typography.Text type="secondary" style={{ fontSize: 13 }}>当前 Excel 独立保存 · 仅影响新任务 · {pendingFile?.name || selectedTemplate?.templateCode}</Typography.Text>
    </>} onCancel={() => { if (!savingProfile) setConfigOpen(false); }} maskClosable={false}
      styles={{ body: { maxHeight: '65vh', overflowY: 'auto' } }}
      footer={<div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 16 }}>
        <Typography.Text type="secondary">假设原图 ID 为 IMG123：<strong>{Number.isInteger(profile.skuStart) && Number.isInteger(profile.skuDigits) && profile.skuStart > 0 && profile.skuDigits > 0
          ? [0, 1, 2].map(i => `IMG123${profile.skuSeparator}${String(profile.skuStart + i).padStart(profile.skuDigits, '0')}`).join('、')
          : '请填写有效的起始序号和补零位数。'}</strong></Typography.Text>
        <Space><Button disabled={savingProfile} onClick={() => setConfigOpen(false)}>取消</Button><Button type="primary" loading={savingProfile} onClick={() => void saveProfile()}>保存生成配置</Button></Space>
      </div>}>
      <Card size="small" style={{ marginBottom: 20 }}>
        <Typography.Text strong>只需维护英文</Typography.Text>
        <Typography.Paragraph style={{ margin: '8px 0' }}>“商品名称”和“英文名称”两列均填写同一个英文标题。</Typography.Paragraph>
        <Typography.Text type="secondary">顺序：平台强制词 → 材质词 → 原图英文关键词 → 平台热词 → 可选节假日 → 场景词。以下为 Mock 词库，请按实际类目维护。</Typography.Text>
      </Card>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: 16 }}>
        {fields.map(([field, label]) => <div key={field}>
          <Typography.Text strong>{label} · English</Typography.Text>
          <Input.TextArea aria-label={`${label} · English`} rows={3} value={wordTexts[field]}
            onChange={event => setWordTexts(current => ({ ...current, [field]: event.target.value }))} style={{ marginTop: 6 }} />
          <Typography.Text type="secondary">每行一个英文词条，新任务时选择使用哪一项。</Typography.Text>
        </div>)}
        <div>
          <Typography.Text strong>图片英文关键词</Typography.Text>
          <Input value="读取每张原图的 English keywords" disabled style={{ marginTop: 6 }} />
          <Typography.Text type="secondary">取图片侵权检测 AI 返回的“英文描述关键词”，无需人工填写。</Typography.Text>
        </div>
      </div>
      <Typography.Title level={5} style={{ marginTop: 24 }}>图片位置与 SKU 编号</Typography.Title>
      <Typography.Paragraph type="secondary">SPU 是一个商品链接；SKU 是该商品下不同尺寸、规格的款式。下方默认值可直接使用。</Typography.Paragraph>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(260px,1fr))', gap: 16 }}>
        <div><Typography.Text strong>SKU 预览图</Typography.Text>
          <InputNumber aria-label="SKU 预览图" min={1} max={10} value={profile.previewIndex} onChange={value => setProfile(current => ({ ...current, previewIndex: value ?? 1 }))} style={{ width: '100%', marginTop: 6 }} />
          <Typography.Text type="secondary">每个 SKU 使用套图第几张。填 2，就是模板组排序第 2 张的成品 URL，不是第 2 个 SKU。</Typography.Text>
        </div>
        <div><Typography.Text strong>SKU 起始序号</Typography.Text>
          <InputNumber aria-label="SKU 起始序号" min={1} max={999999} value={profile.skuStart} onChange={value => setProfile(current => ({ ...current, skuStart: value ?? 1 }))} style={{ width: '100%', marginTop: 6 }} />
          <Typography.Text type="secondary">编号尾数从几开始。填 1：1、2、3…；每张原图重新计数，不是 SKU 数量。</Typography.Text>
        </div>
        <div><Typography.Text strong>SKU 补零位数</Typography.Text>
          <InputNumber aria-label="SKU 补零位数" min={1} max={6} value={profile.skuDigits} onChange={value => setProfile(current => ({ ...current, skuDigits: value ?? 1 }))} style={{ width: '100%', marginTop: 6 }} />
          <Typography.Text type="secondary">尾号至少几位。2 位：01、02；3 位：001、002。超过位数不会截断。</Typography.Text>
        </div>
        <div><Typography.Text strong>SKU 分隔符</Typography.Text>
          <Select aria-label="SKU 分隔符" value={profile.skuSeparator} options={[{ value: '', label: '无（IMG12301）' }, { value: '-', label: '短横线（IMG123-01）' }, { value: '_', label: '下划线（IMG123_01）' }]} onChange={value => setProfile(current => ({ ...current, skuSeparator: value }))} style={{ width: '100%', marginTop: 6 }} />
          <Typography.Text type="secondary">放在“原图 ID”和“递增尾号”之间，只影响货号写法。</Typography.Text>
        </div>
        <div><Typography.Text strong>详情图分隔符</Typography.Text>
          <Select aria-label="详情图分隔符" value={profile.detailSeparator} options={[{ value: '｜', label: '｜' }, { value: '|', label: '|' }]} onChange={value => setProfile(current => ({ ...current, detailSeparator: value }))} style={{ width: '100%', marginTop: 6 }} />
          <Typography.Text type="secondary">SPU 详情中相邻图片 URL 的连接符，默认全角 ｜。</Typography.Text>
        </div>
        <div><Typography.Text strong>轮播图填写层级</Typography.Text>
          <Select aria-label="轮播图填写层级" value={profile.carouselScope} options={[{ value: 'both', label: 'SPU + SKU（与上架示例相同）' }, { value: 'spu', label: '仅 SPU' }]} onChange={value => setProfile(current => ({ ...current, carouselScope: value }))} style={{ width: '100%', marginTop: 6 }} />
          <Typography.Text type="secondary">“SPU + SKU”：商品行和每个规格行都填轮播图；“仅 SPU”：只填商品行。这不改变图片顺序或数量。</Typography.Text>
        </div>
      </div>
      <Typography.Paragraph style={{ marginTop: 20 }}>SKU 数量、规格、价格、库存均来自 Excel 样板行，不由上述编号参数决定。详情图文始终只写 SPU 行。</Typography.Paragraph>
    </Modal>
  </StorePage>;
}
