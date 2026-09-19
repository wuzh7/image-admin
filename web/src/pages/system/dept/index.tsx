import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { ModalForm, PageContainer, ProFormSelect, ProFormText, ProFormTreeSelect, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { Button, Form, message, Space, Switch } from 'antd';
import { useRef, useState } from 'react';
import { addDept, deleteDept, deptTree, getDept, listDept, updateDept } from '@/api/system/dept';
import type { DeptForm, DeptTreeOption, DeptVO } from '@/api/system/dept/types';
import { listRole } from '@/api/system/role';
import type { RoleVO } from '@/api/system/role/types';
import RowActions from '@/components/common/RowActions';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';

const defaultForm: DeptForm = { status: '0', roleIds: [] };

export default function SystemDeptPage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const [form] = Form.useForm<DeptForm>();
  const userInfo = useUserStore(state => state.userInfo);
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('新增部门');
  const [tree, setTree] = useState<DeptTreeOption[]>([]);
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const canAdd = hasPermi(userInfo, ['system:dept:add']);
  const canEdit = hasPermi(userInfo, ['system:dept:edit']);
  const canRemove = hasPermi(userInfo, ['system:dept:remove']);

  const loadOptions = async () => {
    const [treeResult, roleResult] = await Promise.all([deptTree(), listRole({ pageNum: 1, pageSize: 1000, status: '0' })]);
    setTree(treeResult.data || []);
    setRoles(roleResult.data.rows || []);
    return treeResult.data || [];
  };

  const openAdd = async (parent?: DeptVO) => {
    const deptOptions = await loadOptions();
    form.resetFields();
    form.setFieldsValue({ ...defaultForm, parentId: parent?.deptId || deptOptions[0]?.id });
    setTitle('新增部门');
    setOpen(true);
  };

  const openEdit = async (row: DeptVO) => {
    const [, result] = await Promise.all([loadOptions(), getDept(row.deptId)]);
    form.resetFields();
    form.setFieldsValue(result.data);
    setTitle('修改部门');
    setOpen(true);
  };

  const columns: ProColumns<DeptVO>[] = [
    { title: '部门名称', dataIndex: 'deptName', width: 240 },
    {
      title: '状态', dataIndex: 'status', width: 100, valueType: 'select',
      fieldProps: { options: [{ label: '启用', value: '0' }, { label: '停用', value: '1' }] },
      render: (_, row) => <Switch checked={row.status === '0'} disabled checkedChildren="启用" unCheckedChildren="停用" />
    },
    { title: '创建时间', dataIndex: 'createTime', valueType: 'dateTime', search: false, width: 180 },
    {
      title: '操作', valueType: 'option', width: 190,
      render: (_, row) => <RowActions actions={[
        canAdd && { key: 'add', label: '新增下级', icon: <PlusOutlined />, onClick: () => void openAdd(row) },
        canEdit && { key: 'edit', label: '修改', icon: <EditOutlined />, onClick: () => void openEdit(row) },
        canRemove && { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
          confirm: `确认删除部门“${row.deptName}”吗？`, onClick: async () => { await deleteDept(row.deptId); message.success('删除成功'); actionRef.current?.reload(); } }
      ]} />
    }
  ];

  return <PageContainer title="部门管理">
    <ProTable<DeptVO>
      actionRef={actionRef}
      rowKey="deptId"
      columns={columns}
      pagination={false}
      expandable={{ defaultExpandAllRows: true }}
      request={async params => {
        const result = await listDept({ deptName: params.deptName as string | undefined, status: params.status as string | undefined });
        return { data: result.data || [], success: true };
      }}
      toolBarRender={() => canAdd ? [<Button key="add" type="primary" icon={<PlusOutlined />} onClick={() => void openAdd()}>新增</Button>] : []}
    />
    <ModalForm<DeptForm>
      title={title}
      open={open}
      form={form}
      layout="vertical"
      modalProps={{ destroyOnHidden: true, onCancel: () => setOpen(false) }}
      onOpenChange={setOpen}
      onFinish={async values => {
        const data = { ...values, orderNum: 0 };
        values.deptId ? await updateDept(data) : await addDept(data);
        message.success('操作成功');
        actionRef.current?.reload();
        return true;
      }}
    >
      <ProFormText name="deptId" hidden />
      <ProFormTreeSelect name="parentId" label="上级部门" fieldProps={{ treeData: tree, fieldNames: { label: 'label', value: 'id', children: 'children' } }} rules={[{ required: true }]} />
      <ProFormText name="deptName" label="部门名称" rules={[{ required: true }, { max: 30 }]} />
      <ProFormSelect name="roleIds" label="部门角色" mode="multiple" options={roles.map(role => ({ label: role.roleName, value: role.roleId }))} placeholder="可选择多个不同角色" />
      <ProFormSelect name="status" label="状态" options={[{ label: '启用', value: '0' }, { label: '停用', value: '1' }]} rules={[{ required: true }]} />
    </ModalForm>
  </PageContainer>;
}
