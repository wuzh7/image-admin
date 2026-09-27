import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { ModalForm, PageContainer, ProFormText, ProFormTextArea, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { Button, Form, message } from 'antd';
import { useRef, useState } from 'react';
import { addStoreProbe, deleteStoreProbe, getStoreProbe, listStoreProbes, updateStoreProbe, type StoreProbe, type StoreProbeForm } from '@/api/system/storeProbe';
import RowActions from '@/components/common/RowActions';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';

export default function SystemStoreProbePage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const [form] = Form.useForm<StoreProbeForm>();
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const userInfo = useUserStore(state => state.userInfo);
  const canAdd = hasPermi(userInfo, ['system:store-probe:add']);
  const canEdit = hasPermi(userInfo, ['system:store-probe:edit']);
  const canRemove = hasPermi(userInfo, ['system:store-probe:remove']);

  const columns: ProColumns<StoreProbe>[] = [
    { title: '店铺 ID', dataIndex: 'storeId', copyable: true },
    { title: '店铺名称', dataIndex: 'storeName' },
    { title: '备注', dataIndex: 'remarks', search: false, ellipsis: true },
    { title: '创建时间', dataIndex: 'createTime', valueType: 'dateTime', search: false },
    {
      title: '操作', valueType: 'option', search: false,
      render: (_, row) => <RowActions actions={[
        canEdit && { key: 'edit', label: '修改', icon: <EditOutlined />, onClick: async () => { form.setFieldsValue(await getStoreProbe(row.id).then(result => result.data)); setEditing(true); setOpen(true); } },
        canRemove && { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true, confirm: `确认删除店铺“${row.storeId}”吗？`, onClick: async () => { await deleteStoreProbe(row.id); message.success('删除成功'); actionRef.current?.reload(); } }
      ]} />
    }
  ];

  return <PageContainer title="店铺探测配置">
    <ProTable<StoreProbe> actionRef={actionRef} rowKey={row => String(row.id)} columns={columns}
      request={async params => { const result = await listStoreProbes({ pageNum: params.current, pageSize: params.pageSize, storeId: params.storeId, storeName: params.storeName }); return { data: result.data?.rows || [], total: result.data?.total || 0, success: true }; }}
      toolBarRender={() => canAdd ? [<Button key="add" type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setEditing(false); setOpen(true); }}>新增</Button>] : []} />
    <ModalForm<StoreProbeForm> title={editing ? '修改店铺' : '新增店铺'} open={open} form={form} layout="vertical" modalProps={{ destroyOnHidden: true, onCancel: () => setOpen(false) }} onOpenChange={setOpen}
      onFinish={async values => { if (editing) await updateStoreProbe(values); else await addStoreProbe(values); message.success('保存成功'); actionRef.current?.reload(); return true; }}>
      <ProFormText name="id" hidden />
      <ProFormText name="storeId" label="店铺 ID" rules={[{ required: true }, { max: 128 }]} />
      <ProFormText name="storeName" label="店铺名称" rules={[{ max: 255 }]} />
      <ProFormTextArea name="remarks" label="备注" fieldProps={{ rows: 3, maxLength: 500, showCount: true }} />
    </ModalForm>
  </PageContainer>;
}
