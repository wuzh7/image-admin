import { Button, Card, Col, Empty, Form, Input, message, Modal, Row, Select, Tabs, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { createShop, listShops, type CreateStoreShop, type StoreId, type StoreShop } from '@/api/store';
import { listStoreOperators, listStoreReferences, type StoreOperator, type StoreReference } from '@/api/store/reference';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';
import { StorePage, useStoreCategories } from '../common';

type ShopMeta = { operator?: string; sites?: string[]; market?: string; notes?: string };

function shopMeta(shop?: StoreShop): ShopMeta {
  try { return JSON.parse(shop?.configJson || '{}') as ShopMeta; }
  catch { return {}; }
}

export default function StoreOperationsPage() {
  const canAdd = hasPermi(useUserStore(state => state.userInfo), ['store:shop:add']);
  const categories = useStoreCategories();
  const [operators, setOperators] = useState<StoreOperator[]>([]);
  const [platforms, setPlatforms] = useState<StoreReference[]>([]);
  const [sites, setSites] = useState<StoreReference[]>([]);
  const [shops, setShops] = useState<StoreShop[]>([]);
  const [shopId, setShopId] = useState<StoreId>();
  const [createOpen, setCreateOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<CreateStoreShop>();

  useEffect(() => {
    void Promise.all([listShops(), listStoreOperators(), listStoreReferences('PLATFORM', true), listStoreReferences('SITE', true)]).then(([shopsResponse, operatorsResponse, platformsResponse, sitesResponse]) => {
      setShops(shopsResponse.data || []); setOperators(operatorsResponse.data || []); setPlatforms(platformsResponse.data || []); setSites(sitesResponse.data || []);
    }).catch(() => message.error('加载店铺配置失败'));
  }, []);
  const shop = shops.find(item => item.id === shopId);
  const meta = shopMeta(shop);

  return <StorePage title="店铺运营">
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
        <div><Typography.Title level={3} style={{ margin: 0 }}>{shop?.name || '请选择店铺'}</Typography.Title>
          {shop && <Typography.Text type="secondary">{shop.platform || '—'} · {meta.sites?.join('、') || meta.market || '站点待设置'} · 负责人 {meta.operator || '—'}</Typography.Text>}</div>
        {canAdd && <Button type="primary" onClick={() => setCreateOpen(true)}>新增店铺</Button>}
      </div>
      <Select aria-label="切换店铺" allowClear placeholder="选择店铺" value={shopId} onChange={setShopId} style={{ minWidth: 260, marginTop: 20 }} options={shops.map(item => ({ value: item.id, label: `${item.name} · ${item.shopCode || item.id}` }))} />
    </Card>
    <Card style={{ marginTop: 16 }}>
      <Tabs items={[
        ['listing', '上下架动态'], ['traffic', '周期流量'], ['review', '商品评价'],
        ['ad', '广告投放'], ['activity', '活动管理'], ['after-sales', '售后看板'],
      ].map(([key, label]) => ({ key, label, children: <Empty description={shop ? '暂无运营数据' : '请先选择店铺'} /> }))} />
    </Card>
    <Modal title="新增店铺" open={createOpen} onCancel={() => { setCreateOpen(false); form.resetFields(); }} onOk={() => form.submit()} confirmLoading={saving} okText="保存店铺" destroyOnHidden>
      <Form form={form} layout="vertical" initialValues={{ notes: '' }} onFinish={async values => {
        setSaving(true);
        try {
          const saved = (await createShop(values)).data;
          setShops(current => [...current, saved]); setShopId(saved.id); setCreateOpen(false); form.resetFields();
          message.success('店铺已保存，可在上架表格配置中选择');
        } catch (error) { message.error(error instanceof Error ? error.message : '新增店铺失败'); }
        finally { setSaving(false); }
      }}>
        <Row gutter={16}><Col span={12}><Form.Item name="name" label="店铺名称" rules={[{ required: true, whitespace: true }, { max: 80 }]}><Input maxLength={80} /></Form.Item></Col>
          <Col span={12}><Form.Item name="shopCode" label="店铺 ID" extra="唯一；建议使用平台店铺 ID" rules={[{ required: true }, { pattern: /^[A-Za-z0-9_-]{1,80}$/, message: '只支持字母、数字、下划线和短横线' }]}><Input maxLength={80} /></Form.Item></Col>
          <Col span={12}><Form.Item name="operatorUserId" label="店铺负责人" rules={[{ required: true, message: '请选择负责人' }]}><Select showSearch optionFilterProp="label" options={operators.map(item => ({ value: item.id, label: `${item.name}（${item.account}）` }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="platformId" label="所属平台" rules={[{ required: true, message: '请选择平台' }]}><Select showSearch optionFilterProp="label" options={platforms.map(item => ({ value: item.id, label: item.name }))} /></Form.Item></Col>
          <Col span={12}><Form.Item name="siteIds" label="站点" rules={[{ required: true, type: 'array', min: 1, message: '请选择至少一个站点' }]}><Select mode="multiple" showSearch optionFilterProp="label" options={sites.map(item => ({ value: item.id, label: item.name }))} /></Form.Item></Col></Row>
        <Form.Item name="categoryIds" label="经营类目" rules={[{ required: true, type: 'array', min: 1, message: '请选择至少一个经营类目' }]}><Select mode="multiple" showSearch optionFilterProp="label" placeholder="请选择经营类目" options={categories.map(item => ({ label: item.name, value: item.id }))} /></Form.Item>
        <Form.Item name="notes" label="店铺备注" rules={[{ max: 500 }]}><Input.TextArea rows={3} maxLength={500} showCount /></Form.Item>
        <Typography.Text type="secondary">新增店铺只保存目录资料，不会连接或修改真实平台店铺。</Typography.Text>
      </Form>
    </Modal>
  </StorePage>;
}
