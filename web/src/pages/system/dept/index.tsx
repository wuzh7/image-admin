import { DeleteOutlined, EditOutlined, PlusOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { ModalForm, PageContainer, ProFormSelect, ProFormText, ProFormTreeSelect, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { Button, Form, message, Modal, Radio, Switch, Tree, Typography } from 'antd';
import { useRef, useState } from 'react';
import { addDept, deleteDept, deptPermissionTree, deptTree, getDept, getDeptPermissions, listDept, updateDept, updateDeptPermissions } from '@/api/system/dept';
import type { MenuTreeOption } from '@/api/system/menu/types';
import type { DeptForm, DeptTreeOption, DeptVO } from '@/api/system/dept/types';
import { listRole } from '@/api/system/role';
import type { RoleVO } from '@/api/system/role/types';
import RowActions from '@/components/common/RowActions';
import { useUserStore } from '@/stores/userStore';
import { hasPermi, hasRole } from '@/utils/permission';
import { buildDeptTree, disableCurrentDept, filterDeptTree, togglePermissionBranch } from './tree';

const defaultForm: DeptForm = { status: '0', roleIds: [] };

export default function SystemDeptPage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const [form] = Form.useForm<DeptForm>();
  const userInfo = useUserStore(state => state.userInfo);
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('新增部门');
  const [tree, setTree] = useState<DeptTreeOption[]>([]);
  const [roles, setRoles] = useState<RoleVO[]>([]);
  const [parentMode, setParentMode] = useState<'existing' | 'new-root' | 'none'>('existing');
  const [expandedRowKeys, setExpandedRowKeys] = useState<string[]>([]);
  const [expandableKeys, setExpandableKeys] = useState<string[]>([]);
  const editingDeptId = Form.useWatch('deptId', form);
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
    setParentMode(deptOptions.length ? 'existing' : 'none');
    form.resetFields();
    form.setFieldsValue({
      ...defaultForm,
      parentId: parent?.deptId ?? (deptOptions.length ? undefined : 0)
    });
    setTitle('新增部门');
    setOpen(true);
  };

  const openEdit = async (row: DeptVO) => {
    const result = await getDept(row.deptId);
    await loadOptions();
    form.resetFields();
    form.setFieldsValue(result.data);
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

  const parentOptions = disableCurrentDept(tree, editingDeptId);
  if (editingDeptId) {
    parentOptions.unshift({ id: 0, label: '无上级（顶级部门）' });
  }

  return <PageContainer title="部门管理">
    <ProTable<DeptVO>
      actionRef={actionRef}
      rowKey={row => String(row.deptId)}
      columns={columns}
      pagination={false}
      expandable={{ expandedRowKeys, onExpandedRowsChange: keys => setExpandedRowKeys(keys.map(String)) }}
      request={async params => {
        const result = await listDept();
        const name = String(params.deptName || '').trim();
        const status = params.status as string | undefined;
        const rows = buildDeptTree(result.data || []);
        const filtered = name || status ? filterDeptTree(rows, name, status) : rows;
        const keys = (function collect(nodes: DeptVO[]): string[] {
          return nodes.flatMap(node => node.children?.length ? [String(node.deptId), ...collect(node.children)] : []);
        })(filtered);
        setExpandableKeys(keys);
        setExpandedRowKeys(keys);
        return { data: filtered, success: true };
      }}
      toolBarRender={() => [
        <Button key="expand" disabled={!expandableKeys.length} onClick={() => setExpandedRowKeys(expandedRowKeys.length ? [] : expandableKeys)}>
          {expandedRowKeys.length ? '收起全部' : '展开全部'}
        </Button>,
        canAdd && <Button key="add" type="primary" icon={<PlusOutlined />} onClick={() => void openAdd()}>新增</Button>
      ]}
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
          message.error('请选择上级部门');
          return false;
        }
        const data = { ...values, orderNum: 0 };
        if (!values.deptId) {
          if (parentMode === 'existing' && (data.parentId == null || String(data.parentId) === '0')) {
            message.error('请选择已有的上级部门');
            return false;
          }
          if (parentMode === 'new-root') {
            data.parentId = undefined;
            data.parentName = values.parentName?.trim();
          } else if (parentMode === 'none') {
            data.parentId = 0;
            data.parentName = undefined;
          } else {
            data.parentName = undefined;
          }
        }
        values.deptId ? await updateDept(data) : await addDept(data);
        message.success('操作成功');
        actionRef.current?.reload();
        return true;
      }}
    >
      <ProFormText name="deptId" hidden />
      {!editingDeptId && <Form.Item label="上级部门方式">
        <Radio.Group value={parentMode} onChange={event => {
          const mode = event.target.value as typeof parentMode;
          setParentMode(mode);
          form.setFieldsValue({ parentId: mode === 'none' ? 0 : undefined, parentName: undefined });
        }}>
          <Radio value="existing">选择已有部门</Radio>
          <Radio value="new-root">新建上级部门</Radio>
          <Radio value="none">无上级（当前为顶级部门）</Radio>
        </Radio.Group>
      </Form.Item>}
      {(editingDeptId || parentMode === 'existing') && <ProFormTreeSelect name="parentId" label="上级部门" placeholder="请选择已有部门" rules={[{ required: true, message: '请选择上级部门' }]}
        fieldProps={{ treeData: parentOptions, fieldNames: { label: 'label', value: 'id', children: 'children' }, showSearch: true, treeDefaultExpandAll: true }} />}
      {!editingDeptId && parentMode === 'new-root' && <ProFormText name="parentName" label="新上级部门名称" rules={[{ required: true, whitespace: true }, { max: 30 }]} />}
      {!editingDeptId && parentMode === 'none' && <Typography.Text type="secondary">当前部门将成为顶级部门，不归属其他部门。</Typography.Text>}
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
      <Typography.Paragraph type="secondary">部门权限是上限；用户还需拥有对应角色。勾选父目录会同时勾选下级菜单和按钮。</Typography.Paragraph>
      <Tree checkable checkStrictly defaultExpandAll fieldNames={{ title: 'label', key: 'id', children: 'children' }}
        treeData={permissionTree} checkedKeys={permissionIds}
        onCheck={(_, info) => setPermissionIds(ids => togglePermissionBranch(permissionTree, ids, String(info.node.key), info.checked))} />
    </Modal>
  </PageContainer>;
}
