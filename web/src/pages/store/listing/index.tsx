import { Button, Card, Empty, Select, Space, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  downloadListingExport, listListingExports, listRenderBatches, listShops,
  type StoreId, type StoreListingExport, type StoreRenderBatch, type StoreShop,
} from '@/api/store';
import { StorePage, useStoreCategories } from '../common';

export default function ListingPage() {
  const categories = useStoreCategories();
  const [shops, setShops] = useState<StoreShop[]>([]);
  const [shopId, setShopId] = useState<StoreId>();
  const [categoryId, setCategoryId] = useState<StoreId>();
  const [batches, setBatches] = useState<StoreRenderBatch[]>([]);
  const [batchId, setBatchId] = useState<StoreId>();
  const [exports, setExports] = useState<StoreListingExport[]>([]);

  useEffect(() => { void listShops().then(response => setShops(response.data || [])); }, []);
  useEffect(() => {
    if (shopId == null || categoryId == null) return;
    let active = true;
    void listRenderBatches({ shopId, categoryId, status: 'COMPLETED', pageSize: 100 }).then(savedBatches => {
      if (!active) return;
      setBatches(savedBatches.data?.records || []);
    });
    return () => { active = false; };
  }, [shopId, categoryId]);
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
    setBatches([]); setBatchId(undefined); setExports([]);
  };

  return <StorePage title="上架表格">
    <Card title="筛选上架表格">
      <Space wrap>
        <Select allowClear placeholder="选择店铺" value={shopId} options={shops.map(shop => ({ value: shop.id, label: shop.name }))} onChange={value => changeScope(value, categoryId)} style={{ width: 220 }} />
        <Select allowClear placeholder="选择类目" value={categoryId} options={categories.map(item => ({ value: item.id, label: item.name }))} onChange={value => changeScope(shopId, value)} style={{ width: 220 }} />
        <Select allowClear placeholder="选择已完成批次" value={batchId} options={batches.map(batch => ({ value: batch.id, label: `批次 ${batch.id} · ${batch.totalCount} 张` }))} onChange={setBatchId} style={{ width: 360 }} />
      </Space>
    </Card>
    <Card title="上架表格列表" style={{ marginTop: 16 }}>
      {exports.length ? exports.map(row => <div key={String(row.id)} style={{ marginTop: 16 }}><Space wrap>
        <span>{row.filename}</span><span>{row.status}</span>
        {row.errorMessage && <Typography.Text type="danger">{row.errorMessage}</Typography.Text>}
        {row.status === 'COMPLETED' && <Button onClick={() => void download(row)}>下载</Button>}
      </Space></div>) : <Empty description="暂无上架表格记录" />}
    </Card>
  </StorePage>;
}
