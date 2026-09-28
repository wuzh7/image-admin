import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import {
  ModalForm,
  PageContainer,
  ProFormRadio,
  ProFormText,
  ProFormTextArea,
  ProTable,
  type ActionType,
  type ProColumns
} from '@ant-design/pro-components';
import { useBoolean } from 'ahooks';
import { Button, Form, message, Popconfirm, Switch } from 'antd';
import { useRef, useState } from 'react';
import type { RoleForm, RoleQuery, RoleVO } from '@/api/system/role/types';
import { roleMenuTreeselect } from '@/api/system/menu';
import { addRole, changeRoleStatus, delRole, getRole, listRole, updateRole } from '@/api/system/role';
import RowActions from '@/components/common/RowActions';
import { useDateRangeQuery } from '@/hooks/useDateRangeQuery';
import { useDict } from '@/hooks/useDict';
import { useTableSelection } from '@/hooks/useTableSelection';
import { useTableScroll } from '@/hooks/useTableScroll';
import { useUserStore } from '@/stores/userStore';
import { dictOptions } from '@/utils/dict';
import { confirmAction } from '@/utils/modal';
import { hasPermi } from '@/utils/permission';
import { toPageQuery, toTableData } from '@/utils/wuzh';
import RolePermissionModal from './components/RolePermissionModal';

const defaultRoleForm: RoleForm = {
  status: '0',
  menuCheckStrictly: true,
  dataScope: '1',
  menuIds: []
};

export default function SystemRolePage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const { tableScroll } = useTableScroll(1132);
  const [form] = Form.useForm<RoleForm>();
  const userInfo = useUserStore(state => state.userInfo);
  const dicts = useDict('sys_normal_disable');
  const {
    ids: selectedIds,
    selectedOne,
    selectedRows,
    handleSelectionChange,
    clearSelection
  } = useTableSelection<RoleVO>(row => row.roleId);
  const [modalOpen, { setTrue: openModal, setFalse: closeModal }] = useBoolean(false);
  const [modalTitle, setModalTitle] = useState('');
  const [permissionOpen, { setTrue: openPermissionModal, setFalse: closePermissionModal }] = useBoolean(false);
  const [permissionRoleId, setPermissionRoleId] = useState<string | number>();
  const { applyDateRange: applyCreateTimeDateRange } = useDateRangeQuery();

  const canAdd = hasPermi(userInfo, ['system:role:add']);
  const canEdit = hasPermi(userInfo, ['system:role:edit']);
  const canRemove = hasPermi(userInfo, ['system:role:remove']);
  const canPermission = hasPermi(userInfo, ['system:role:permission']);

  const openAdd = () => {
    form.resetFields();
    setModalTitle('添加角色');
    form.setFieldsValue(defaultRoleForm);
    openModal();
  };

  const openEdit = async (row?: RoleVO) => {
    const target = row || selectedOne;
    if (!target?.roleId) return;
    const [res, menuRes] = await Promise.all([getRole(target.roleId), roleMenuTreeselect(target.roleId)]);
    form.resetFields();
    setModalTitle('修改角色');
    form.setFieldsValue({
      ...defaultRoleForm,
      ...res.data,
      menuIds: menuRes.data.checkedKeys
    });
    openModal();
  };

  const submitForm = async (fields: RoleForm) => {
    const values = { ...form.getFieldsValue(true), ...fields };
    if (values.roleId) {
      await updateRole(values);
    } else {
      await addRole(values);
    }
    message.success('操作成功');
    form.resetFields();
    actionRef.current?.reload();
    return true;
  };

  const handleDelete = async (row?: RoleVO) => {
    const ids = row?.roleId || selectedIds;
    if (!ids || (Array.isArray(ids) && ids.length === 0)) return;
    await delRole(ids);
    message.success('删除成功');
    clearSelection();
    actionRef.current?.reloadAndRest?.();
  };

  const handleStatusChange = async (row: RoleVO, checked: boolean) => {
    const status = checked ? '0' : '1';
    const text = status === '0' ? '启用' : '停用';
    try {
      await confirmAction(`确认要"${text}""${row.roleName}"角色吗?`);
      await changeRoleStatus(row.roleId, status);
      message.success(`${text}成功`);
      actionRef.current?.reload();
    } catch {
      actionRef.current?.reload();
    }
  };

  const openPermission = (row: RoleVO) => {
    setPermissionRoleId(row.roleId);
    openPermissionModal();
  };

  const columns: ProColumns<RoleVO>[] = [
    {
      title: '角色名称',
      dataIndex: 'roleName',
      width: 160
    },
    {
      title: '状态',
      dataIndex: 'status',
      valueType: 'select',
      width: 100,
      fieldProps: {
        options: dictOptions(dicts.sys_normal_disable)
      },
      render: (_, row) => (
        <Switch
          checked={row.status === '0'}
          checkedChildren="启用"
          unCheckedChildren="停用"
          disabled={!canEdit}
          onChange={checked => handleStatusChange(row, checked)}
        />
      )
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      valueType: 'dateTime',
      search: false,
      width: 170
    },
    {
      title: '创建时间',
      dataIndex: 'createTimeRange',
      valueType: 'dateTimeRange',
      hideInTable: true
    },
    {
      title: '操作',
      valueType: 'option',
      width: 160,
      fixed: 'right',
      render: (_, row) => {
        if (String(row.roleId) === '1761300000000000001') return null;
        return (
          <RowActions
            actions={[
              canEdit && { key: 'edit', label: '修改', icon: <EditOutlined />, onClick: () => openEdit(row) },
              canRemove && {
                key: 'delete',
                label: '删除',
                icon: <DeleteOutlined />,
                danger: true,
                confirm: `是否确认删除角色“${row.roleName}”？`,
                onClick: () => handleDelete(row)
              },
              canPermission && {
                key: 'permission',
                label: '分配权限',
                icon: <SafetyCertificateOutlined />,
                onClick: () => openPermission(row)
              },
            ]}
          />
        );
      }
    }
  ];

  return (
    <PageContainer title="角色管理">
      <ProTable<RoleVO, RoleQuery & { createTimeRange?: [string, string] }>
        actionRef={actionRef}
        rowKey="roleId"
        columns={columns}
        scroll={tableScroll}
        search={{ labelWidth: 90 }}
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        rowSelection={{
          selectedRowKeys: selectedIds,
          onChange: handleSelectionChange
        }}
        request={async params => {
          const { createTimeRange, ...tableParams } = params;
          const query = applyCreateTimeDateRange(toPageQuery(tableParams), createTimeRange);
            const res = await listRole(query);
          return toTableData(res);
        }}
        toolbar={{ title: '角色列表' }}
        toolBarRender={() => [
          canAdd && (
            <Button key="add" type="primary" icon={<PlusOutlined />} onClick={openAdd}>
              新增
            </Button>
          ),
          canEdit && (
            <Button key="edit" disabled={!selectedOne} icon={<EditOutlined />} onClick={() => openEdit()}>
              修改
            </Button>
          ),
          canRemove && (
            <Popconfirm
              key="delete"
              title={`是否确认删除角色“${selectedRows.map(row => row.roleName).join("、")}”？`}
              onConfirm={() => handleDelete()}
            >
              <Button danger disabled={!selectedIds.length} icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )
        ]}
      />

      <ModalForm<RoleForm>
        title={modalTitle}
        open={modalOpen}
        width={720}
        form={form}
        layout="vertical"
        initialValues={defaultRoleForm}
        modalProps={{
          destroyOnHidden: true,
          onCancel: () => {
            closeModal();
            form.resetFields();
          }
        }}
        onOpenChange={open => !open && closeModal()}
        onFinish={submitForm}
      >
        <ProFormText name="roleId" hidden />
        <div className="form-grid">
          <ProFormText
            name="roleName"
            label="角色名称"
            placeholder="请输入角色名称"
            rules={[{ required: true, whitespace: true, message: '角色名称不能为空' }, { max: 30 }]}
          />
          <ProFormRadio.Group name="status" label="状态" options={dictOptions(dicts.sys_normal_disable)} />
        </div>
        <ProFormTextArea name="remark" label="备注" placeholder="请输入内容" fieldProps={{ rows: 3 }} />
      </ModalForm>

      <RolePermissionModal
        open={permissionOpen}
        roleId={permissionRoleId}
        onCancel={closePermissionModal}
        onSuccess={() => {
          closePermissionModal();
          actionRef.current?.reload();
        }}
      />
    </PageContainer>
  );
}
