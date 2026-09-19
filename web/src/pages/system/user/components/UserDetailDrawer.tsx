import { ProDescriptions } from '@ant-design/pro-components';
import { useRequest } from 'ahooks';
import { Divider, Drawer, Switch } from 'antd';
import { useEffect, useMemo } from 'react';
import { getUser } from '@/api/system/user';

interface UserDetailDrawerProps {
  open: boolean;
  userId?: string | number;
  onClose: () => void;
}

export default function UserDetailDrawer({ open, userId, onClose }: UserDetailDrawerProps) {
  const { data, loading, runAsync: loadUser, mutate } = useRequest(getUser, { manual: true });

  useEffect(() => {
    if (open && userId) {
      loadUser(userId);
    }
  }, [loadUser, open, userId]);

  const user = data?.data.user;
  const roleIds = useMemo(() => (data?.data.roleIds || []).map(String), [data?.data.roleIds]);
  const roleNames = (data?.data.roles || [])
    .filter(role => roleIds.includes(String(role.roleId)))
    .map(role => role.roleName)
    .join('、');

  const closeDrawer = () => {
    onClose();
    mutate(undefined);
  };

  return (
    <Drawer title="用户信息详情" open={open} size={680} onClose={closeDrawer} destroyOnHidden>
      <ProDescriptions
        loading={loading}
        column={2}
        bordered
        dataSource={{
          ...user,
          statusDisplay: user?.status === '0' ? '正常' : '停用',
          roleNames: roleNames || '-'
        }}
        columns={[
          { title: '用户账号', dataIndex: 'userName' },
          { title: '用户名称', dataIndex: 'nickName' },
          {
            title: '用户状态',
            dataIndex: 'statusDisplay',
            render: (_, record) => (
              <Switch checked={record.status === '0'} checkedChildren="正常" unCheckedChildren="停用" disabled />
            )
          },
          { title: '角色', dataIndex: 'roleNames', span: 2 }
        ]}
      />
      <Divider />
      <ProDescriptions
        column={2}
        bordered
        dataSource={user}
        columns={[
          { title: '创建时间', dataIndex: 'createTime', valueType: 'dateTime' },
          { title: '更新时间', dataIndex: 'updateTime', valueType: 'dateTime' },
          { title: '最后登录IP', dataIndex: 'loginIp' },
          { title: '最后登录时间', dataIndex: 'loginDate', valueType: 'dateTime' },
          { title: '备注', dataIndex: 'remark', span: 2 }
        ]}
      />
    </Drawer>
  );
}
