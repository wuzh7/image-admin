import {
  DeleteOutlined,
  EditOutlined,
  KeyOutlined,
  LockOutlined,
  PlusOutlined,
  UserSwitchOutlined
} from '@ant-design/icons';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { useBoolean } from 'ahooks';
import { Button, Form, Input, message, Modal, Popconfirm, Switch } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { RoleVO } from '@/api/system/role/types';
import type { DeptTreeOption } from '@/api/system/dept/types';
import { deptTree, getDeptRoles } from '@/api/system/dept';
import type { UserForm, UserQuery, UserVO } from '@/api/system/user/types';
import { getConfigKey } from '@/api/system/config';
import {
  addUser,
  changeUserStatus,
  delUser,
  getUser,
  listUser,
  resetUserPwd,
  unlockUser,
  updateUser
} from '@/api/system/user';
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
import UserDetailDrawer from './components/UserDetailDrawer';
import UserFormModal from './components/UserFormModal';

const defaultForm: UserForm = {
  status: '0',
  roleIds: []
};

export default function SystemUserPage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const { tableScroll } = useTableScroll(1220);
  const [form] = Form.useForm<UserForm>();
  const userInfo = useUserStore(state => state.userInfo);
  const dicts = useDict('sys_normal_disable');
  const [roleOptions, setRoleOptions] = useState<RoleVO[]>([]);
  const [deptOptions, setDeptOptions] = useState<DeptTreeOption[]>([]);
  const [initPassword, setInitPassword] = useState('');
  const {
    ids: selectedIds,
    selectedOne,
    handleSelectionChange,
    clearSelection
  } = useTableSelection<UserVO>(row => row.userId);
  const [modalOpen, { setTrue: openModal, setFalse: closeModal }] = useBoolean(false);
  const [modalTitle, setModalTitle] = useState('');
  const [viewOpen, { setTrue: openViewDrawer, setFalse: closeViewDrawer }] = useBoolean(false);
  const [viewUserId, setViewUserId] = useState<string | number>();
  const { applyDateRange: applyCreateTimeDateRange } = useDateRangeQuery();
  const currentUserId = userInfo?.user?.userId;

  const canAdd = hasPermi(userInfo, ['system:user:add']);
  const canEdit = hasPermi(userInfo, ['system:user:edit']);
  const canRemove = hasPermi(userInfo, ['system:user:remove']);
  const canResetPwd = hasPermi(userInfo, ['system:user:resetPwd']);

  useEffect(() => {
    getConfigKey('sys.user.initPassword').then(res => {
      setInitPassword(res.data || '');
    });
  }, []);

  const openAdd = async () => {
    const [res, depts] = await Promise.all([getUser(), deptTree()]);
    setModalTitle('新增用户');
    setDeptOptions(depts.data || []);
    setRoleOptions([]);
    form.resetFields();
    form.setFieldsValue({
      ...defaultForm,
      password: initPassword
    });
    openModal();
  };

  const openEdit = async (row?: UserVO) => {
    const target = row || selectedOne;
    if (!target?.userId) return;
    const [res, depts] = await Promise.all([getUser(target.userId), deptTree()]);
    const user = res.data.user || {};
    const deptRoles = user.deptId ? await getDeptRoles(user.deptId) : undefined;
    setModalTitle('修改用户');
    setDeptOptions(depts.data || []);
    setRoleOptions(
      Array.from(new Map([...(deptRoles?.data || []), ...(user.roles || [])].map(role => [role.roleId, role])).values())
    );
    form.resetFields();
    form.setFieldsValue({
      ...user,
      password: '',
      roleIds: res.data.roleIds || []
    });
    openModal();
  };

  const openView = async (row: UserVO) => {
    if (!row.userId) return;
    setViewUserId(row.userId);
    openViewDrawer();
  };

  const submitForm = async (values: UserForm) => {
    const submitValues = { ...values };
    if (submitValues.userId && String(submitValues.userId) === String(currentUserId)) {
      submitValues.roleIds = undefined;
    }
    if (submitValues.userId) {
      await updateUser(submitValues);
    } else {
      await addUser(submitValues);
    }
    message.success('操作成功');
    form.resetFields();
    actionRef.current?.reload();
    return true;
  };

  const handleDelete = async (row?: UserVO) => {
    const ids = row?.userId || selectedIds;
    if (!ids || (Array.isArray(ids) && ids.length === 0)) return;
    await delUser(ids);
    message.success('删除成功');
    clearSelection();
    actionRef.current?.reloadAndRest?.();
  };

  const handleUnlock = async () => {
    if (!selectedOne?.userId) return;
    await confirmAction(`是否确认解锁用户编号为"${selectedOne.userId}"的数据项?`);
    await unlockUser(selectedOne.userId);
    message.success(`用户编号"${selectedOne.userId}"解锁成功`);
    clearSelection();
    actionRef.current?.reloadAndRest?.();
  };

  const handleResetPwd = (row: UserVO) => {
    let password = '';
    Modal.confirm({
      title: `请输入"${row.nickName || row.userName}"的新密码`,
      content: (
        <Input.Password
          autoFocus
          placeholder="新密码"
          onChange={event => {
            password = event.target.value;
          }}
        />
      ),
      onOk: async () => {
        if (!/^.{5,20}$/.test(password)) {
          message.warning('用户密码长度必须介于 5 和 20 之间');
          return Promise.reject();
        }
        if (/<|>|"|'|\||\\/.test(password)) {
          message.warning('不能包含非法字符：< > " \' \\ |');
          return Promise.reject();
        }
        if (!row.userId) return;
        await resetUserPwd(row.userId, password);
        message.success(`修改成功，新密码是：${password}`);
      }
    });
  };

  const handleStatusChange = async (row: UserVO, checked: boolean) => {
    if (!row.userId) return;
    const status = checked ? '0' : '1';
    const text = status === '0' ? '启用' : '停用';
    const previousStatus = row.status;
    try {
      await confirmAction(`确认要"${text}""${row.nickName || row.userName}"用户吗?`);
      await changeUserStatus(row.userId, status);
      message.success(`${text}成功`);
      actionRef.current?.reload();
    } catch {
      row.status = previousStatus;
      actionRef.current?.reload();
    }
  };

  const columns: ProColumns<UserVO>[] = [
    {
      title: '用户编号',
      dataIndex: 'userId',
      width: 110,
      search: false,
      hideInTable: true
    },
    {
      title: '用户账号',
      dataIndex: 'userName',
      width: 160
    },
    {
      title: '用户名称',
      dataIndex: 'nickName',
      render: (_, row) => (
        <Button type="link" size="small" onClick={() => openView(row)}>
          {row.nickName || row.userName}
        </Button>
      ),
      width: 180
    },
    { title: '所属部门', dataIndex: 'deptName', width: 150, search: false, ellipsis: true },
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
        if (row.userId === '1761100000000000001') return null;
        return (
          <RowActions
            actions={[
              canEdit && { key: 'edit', label: '修改', icon: <EditOutlined />, onClick: () => openEdit(row) },
              canRemove && {
                key: 'delete',
                label: '删除',
                icon: <DeleteOutlined />,
                danger: true,
                confirm: `是否确认删除用户"${row.nickName || row.userName}"？`,
                onClick: () => handleDelete(row)
              },
              canResetPwd && {
                key: 'resetPwd',
                label: '重置密码',
                icon: <KeyOutlined />,
                onClick: () => handleResetPwd(row)
              },
              canEdit && {
                key: 'authRole',
                label: '分配角色',
                icon: <UserSwitchOutlined />,
                onClick: () => history.push(`/system/user-auth/role/${row.userId}`)
              }
            ]}
          />
        );
      }
    }
  ];

  return (
    <PageContainer title="用户管理">
      <main>
        <ProTable<UserVO, UserQuery & { createTimeRange?: [string, string] }>
          actionRef={actionRef}
          rowKey="userId"
          columns={columns}
          scroll={tableScroll}
          search={{ labelWidth: 90, defaultCollapsed: false }}
          pagination={{ defaultPageSize: 10, showSizeChanger: true }}
          rowSelection={{
            selectedRowKeys: selectedIds,
            onChange: handleSelectionChange
          }}
          request={async params => {
            const { createTimeRange, ...tableParams } = params;
            const query = applyCreateTimeDateRange(toPageQuery(tableParams), createTimeRange);
            const res = await listUser(query);
            return toTableData(res);
          }}
          toolbar={{
            title: '用户列表'
          }}
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
                title={`是否确认删除用户编号为"${selectedIds}"的数据项？`}
                onConfirm={() => handleDelete()}
              >
                <Button danger disabled={!selectedIds.length} icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>
            ),
            canEdit && (
              <Button key="unlock" disabled={!selectedOne} icon={<LockOutlined />} onClick={handleUnlock}>
                解锁
              </Button>
            ),
          ]}
        />
      </main>

      <UserFormModal
        open={modalOpen}
        title={modalTitle}
        form={form}
        initialValues={defaultForm}
        currentUserId={currentUserId}
        roleOptions={roleOptions}
        deptOptions={deptOptions}
        onDeptChange={async deptId => {
          if (!deptId) { setRoleOptions([]); return; }
          const result = await getDeptRoles(deptId);
          setRoleOptions(result.data || []);
        }}
        statusOptions={dictOptions(dicts.sys_normal_disable)}
        onClose={closeModal}
        onFinish={submitForm}
      />

      <UserDetailDrawer
        open={viewOpen}
        userId={viewUserId}
        onClose={closeViewDrawer}
      />
    </PageContainer>
  );
}
