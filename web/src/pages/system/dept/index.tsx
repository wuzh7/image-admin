import { DeleteOutlined, EditOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { ModalForm, PageContainer, ProFormSelect, ProFormText, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { AutoComplete, Button, Form, message, Modal, Switch, Tree, Typography } from 'antd';
import { useRef, useState } from 'react';
import { addDept, deleteDept, deptPermissionTree, deptTree, getDept, getDeptPermissions, listDept, updateDept, updateDeptPermissions } from '@/api/system/dept';
import type { MenuTreeOption } from '@/api/system/menu/types';
import type { DeptForm, DeptTreeOption, DeptVO } from '@/api/system/dept/types';
import { listRole } from '@/api/system/role';
import type { RoleVO } from '@/api/system/role/types';
import RowActions from '@/components/common/RowActions';
import { useUserStore } from '@/stores/userStore';
import { hasPermi, hasRole } from '@/utils/permission';

const defaultForm: DeptForm = { status: '0', roleIds: [] };

function flattenTree(nodes: DeptTreeOption[], path = ''): Array<{ label: string; value: string; deptId: string | number }> {
  return nodes.flatMap(node => [
    { label: `${path}${node.label}`, value: node.label, deptId: node.id },
    ...flattenTree(node.children || [], `${path}${node.label} / `)
  ]);
}

export default function SystemDeptPage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const [form] = Form.useForm<DeptForm>();
  const userInfo = useUserStore(state => state.userInfo);
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('新增部门');
  const [tree, setTree] = useState<DeptTreeOption[]>([]);
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const [permissionDept, setPermissionDept] = useState<DeptVO>();
  const [permissionTree, setPermissionTree] = useState<MenuTreeOption[]>([]);
  const [permissionIds, setPermissionIds] = useState<Array<string | number>>([]);
  const canManage = hasRole(userInfo, ['superadmin']);
  const canAdd = canManage && hasPermi(userInfo, ['system:dept:add']);
  const canEdit = canManage && hasPermi(userInfo, ['system:dept:edit']);
  const canRemove = canManage && hasPermi(userInfo, ['system:dept:remove']);
  const canPermission = canManage && hasPermi(userInfo, ['system:dept:permission']);

  const loadOptions = async () => {
    const [treeResult, roleResult] = await Promise.all([deptTree(), listRole({ pageNum: 1, pageSize: 1000, status: '0' })]);
    setTree(treeResult.data || []);
    setRoles((roleResult.data.rows || []).filter(role => role.roleKey !== 'superadmin'));
    return treeResult.data || [];
  };

  const openAdd = async (parent?: DeptVO) => {
    const deptOptions = await loadOptions();
    form.resetFields();
    form.setFieldsValue({
      ...defaultForm,
      parentId: parent?.deptId || deptOptions[0]?.id,
      parentName: parent?.deptName || deptOptions[0]?.label || ''
    });
    setTitle('新增部门');
    setOpen(true);
  };

  const openEdit = async (row: DeptVO) => {
    const [deptOptions, result] = await Promise.all([loadOptions(), getDept(row.deptId)]);
    form.resetFields();
    form.setFieldsValue({ ...result.data, parentName: String(result.data.parentId) === '0' ? '顶级部门'
      : flattenTree(deptOptions).find(option => String(option.deptId) === String(result.data.parentId))?.value });
    setTitle('修改部门');
    setOpen(true);
  };

  const openPermission = async (row: DeptVO) => {
    const [treeResult, selectedResult] = await Promise.all([deptPermissionTree(), getDeptPermissions(row.deptId)]);
    setPermissionTree(treeResult.data || []);
    setPermissionIds(selectedResult.data || []);
    setPermissionDept(row);
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
        canPermission && { key: 'permission', label: '设置权限', icon: <SafetyCertificateOutlined />, onClick: () => void openPermission(row) },
        canRemove && { key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
          confirm: `确认删除部门“${row.deptName}”吗？`, onClick: async () => { await deleteDept(row.deptId); message.success('删除成功'); actionRef.current?.reload(); } }
      ]} />
    }
  ];

  const parentOptions = flattenTree(tree);

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
        if (values.deptId && values.parentId == null) {
          message.error('修改部门时请选择已有的上级部门');
          return false;
        }
        const data = { ...values, orderNum: 0 };
        values.deptId ? await updateDept(data) : await addDept(data);
        message.success('操作成功');
        actionRef.current?.reload();
        return true;
      }}
    >
      <ProFormText name="deptId" hidden />
      <Form.Item name="parentName" label="上级部门" rules={[{ required: true, whitespace: true, message: '请输入上级部门' }]}>
        <AutoComplete
          options={parentOptions}
          placeholder="可输入并匹配部门；无匹配时保存会新建"
          onSelect={(_, option) => form.setFieldValue('parentId', option.deptId)}
          onChange={(_, option) => form.setFieldValue('parentId', Array.isArray(option) ? undefined : option?.deptId)}
          filterOption={(input, option) => (option?.label || '').toLowerCase().includes(input.toLowerCase())}
        />
      </Form.Item>
      <ProFormText name="parentId" hidden />
      <ProFormText name="deptName" label="部门名称" rules={[{ required: true }, { max: 30 }]} />
      <ProFormSelect name="roleIds" label="部门角色" mode="multiple" options={roles.map(role => ({ label: role.roleName, value: role.roleId }))} placeholder="可选择多个不同角色" />
      <ProFormSelect name="status" label="状态" options={[{ label: '启用', value: '0' }, { label: '停用', value: '1' }]} rules={[{ required: true }]} />
    </ModalForm>
    <Modal title={`设置部门权限 · ${permissionDept?.deptName || ''}`} open={!!permissionDept} width={760}
      onCancel={() => setPermissionDept(undefined)} onOk={async () => {
        if (!permissionDept) return;
        await updateDeptPermissions(permissionDept.deptId, permissionIds);
        message.success('部门权限已更新，部门用户需要重新登录');
        setPermissionDept(undefined);
      }}>
      <Typography.Paragraph type="secondary">部门权限是上限；用户还需拥有对应角色。勾选父目录只开放目录，菜单和按钮需分别勾选。</Typography.Paragraph>
      <Tree checkable checkStrictly defaultExpandAll fieldNames={{ title: 'label', key: 'id', children: 'children' }}
        treeData={permissionTree} checkedKeys={permissionIds}
        onCheck={keys => setPermissionIds((Array.isArray(keys) ? keys : keys.checked).map(String))} />
    </Modal>
  </PageContainer>;
}
