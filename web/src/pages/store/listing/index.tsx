import { Button, Card, Empty, message, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  createListingExport, downloadListingExport, listListingExports, listListingTemplates, listRenderBatches, listShops,
  type StoreId, type StoreListingExport, type StoreRenderBatch, type StoreShop, type StoreListingTemplate,
} from '@/api/store';
import { StorePage, useStoreCategories } from '../common';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';

export default function ListingPage() {
  const userInfo = useUserStore(state => state.userInfo);
  const canDownload = hasPermi(userInfo, ['store:listing-export:download']);
  const canGenerate = hasPermi(userInfo, ['store:listing-export:add']);
  const categories = useStoreCategories();
  const [shops, setShops] = useState<StoreShop[]>([]);
  const [shopId, setShopId] = useState<StoreId>();
  const [categoryId, setCategoryId] = useState<StoreId>();
  const [batches, setBatches] = useState<StoreRenderBatch[]>([]);
  const [batchId, setBatchId] = useState<StoreId>();
  const [templates, setTemplates] = useState<StoreListingTemplate[]>([]);
  const [templateId, setTemplateId] = useState<StoreId>();
  const [submitting, setSubmitting] = useState(false);
  const [exports, setExports] = useState<StoreListingExport[]>([]);

  useEffect(() => { void listShops().then(response => setShops(response.data || [])); }, []);
  useEffect(() => {
    if (shopId == null || categoryId == null) return;
    let active = true;
    if (canGenerate) void listListingTemplates({ shopId, categoryId }).then(response => { if (active) setTemplates(response.data || []); });
    void listRenderBatches({ shopId, categoryId, status: 'COMPLETED', pageSize: 100 }).then(savedBatches => {
      if (!active) return;
      setBatches(savedBatches.data?.records || []);
    });
    return () => { active = false; };
  }, [shopId, categoryId, canGenerate]);
  useEffect(() => {
    const load = () => { void listListingExports({ batchId, shopId, categoryId }).then(response => setExports(response.data || [])); };
    load();
    const interval = window.setInterval(load, 5000);
    return () => window.clearInterval(interval);
  }, [batchId, shopId, categoryId]);

  const download = async (row: StoreListingExport) => {
    const url = URL.createObjectURL(await downloadListingExport(row.id));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = row.filename; anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 60000);
  };
  const changeScope = (shop?: StoreId, category?: StoreId) => {
    setShopId(shop); setCategoryId(category);
    setBatches([]); setBatchId(undefined); setExports([]); setTemplates([]); setTemplateId(undefined);
  };

  return <StorePage title="上架表格">
    <Card title="筛选上架表格">
      <Space wrap>
        <Select aria-label="表格店铺" disabled={submitting} allowClear placeholder="选择店铺" value={shopId} options={shops.map(shop => ({ value: shop.id, label: shop.name }))} onChange={value => changeScope(value, categoryId)} style={{ width: 220 }} />
        <Select aria-label="表格类目" disabled={submitting} allowClear placeholder="选择类目" value={categoryId} options={categories.map(item => ({ value: item.id, label: item.name }))} onChange={value => changeScope(shopId, value)} style={{ width: 220 }} />
        <Select aria-label="已完成渲染批次" disabled={submitting} allowClear placeholder="选择已完成批次" value={batchId} options={batches.map(batch => ({ value: batch.id, label: `批次 ${batch.id} · ${batch.totalCount} 张` }))} onChange={setBatchId} style={{ width: 360 }} />
      </Space>
    </Card>
    {canGenerate && <Card title="使用已渲染成品生成表格" style={{ marginTop: 16 }}>
      <Space wrap>
        <Select aria-label="成品表格模板" allowClear placeholder="选择上架表格" value={templateId} disabled={submitting} options={templates.filter(template => template.configJson).map(template => ({ value: template.id, label: template.templateCode || template.originalFilename }))} onChange={setTemplateId} style={{ width: 300 }} />
        <Button type="primary" loading={submitting} disabled={batchId == null || shopId == null || templateId == null} onClick={async () => {
          if (batchId == null || shopId == null || templateId == null) return;
          setSubmitting(true);
          try {
            await createListingExport(batchId, shopId, templateId);
            setExports((await listListingExports({ batchId, shopId, categoryId })).data || []);
            message.success('已提交表格任务，使用所选批次的渲染成品');
          } finally { setSubmitting(false); }
        }}>生成表格</Button>
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>先选择店铺、类目和已完成批次。仅渲染的批次可直接生成表格，无需再次渲染；使用原图请到正式图库选择“仅生成表格”。</Typography.Paragraph>
    </Card>}
    <Card title="上架表格列表" style={{ marginTop: 16 }}>
      {exports.length ? exports.map(row => <div key={String(row.id)} style={{ marginTop: 16 }}><Space wrap>
        <span>{row.filename}</span><span>{row.batchId == null ? '原图表格' : '渲染成品表格'}</span><span>{{ WAITING: '等待渲染', PENDING: '等待生成', GENERATING: '生成中', COMPLETED: '已完成', FAILED: '生成失败' }[row.status] || row.status}</span>
        {row.errorMessage && <Typography.Text type="danger">系统错误，请稍后重试</Typography.Text>}
        {canDownload && row.status === 'COMPLETED' && <Button onClick={() => void download(row)}>下载</Button>}
      </Space></div>) : <Empty description="暂无上架表格记录" />}
    </Card>
  </StorePage>;
}
