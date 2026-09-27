import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Button, Form, Input, message, Modal, Space, Table } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { listStoreReferences, saveStoreReference, type StoreReference } from '@/api/store/reference';

export default function StoreReferencePage() {
  const kind = window.location.pathname.endsWith('/sites') ? 'SITE' : 'PLATFORM';
  const title = kind === 'SITE' ? '站点维护' : '平台维护';
  const [rows, setRows] = useState<StoreReference[]>([]);
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<{ name: string }>();
  const load = useCallback(async () => setRows((await listStoreReferences(kind)).data || []), [kind]);
  useEffect(() => { void load(); }, [load]);
  const save = async () => {
    const values = await form.validateFields(); setSaving(true);
    try { await saveStoreReference(kind, { name: values.name.trim(), status: '0' }); message.success(`${title}已保存`); setOpen(false); await load(); }
    finally { setSaving(false); }
  };
  return <PageContainer title={title}><ProCard><Space style={{ marginBottom: 16 }}><Button type="primary" icon={<PlusOutlined />} onClick={() => { form.setFieldsValue({ name: '' }); setOpen(true); }}>新增</Button><Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button></Space>
    <Table rowKey={row => String(row.id)} dataSource={rows} pagination={false} columns={[{ title: '名称', dataIndex: 'name' }]} /></ProCard>
    <Modal title={`新增${title}`} open={open} onOk={() => void save()} confirmLoading={saving} onCancel={() => setOpen(false)} destroyOnHidden><Form form={form} layout="vertical"><Form.Item name="name" label="名称" rules={[{ required: true, whitespace: true }, { max: 50 }]}><Input /></Form.Item></Form></Modal>
  </PageContainer>;
}
