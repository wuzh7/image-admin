import type { ColumnsType } from 'antd/es/table';
import { SearchOutlined } from '@ant-design/icons';
import { useBoolean, useRequest } from 'ahooks';
import { Button, Form, Input, Modal, Space, Table, Tag } from 'antd';
import { useEffect, useState } from 'react';
import type { UserQuery, UserVO } from '@/api/system/user/types';
import { listUser, optionSelect } from '@/api/system/user';
import DictTag from '@/components/common/DictTag';
import { useDict } from '@/hooks/useDict';

export interface UserSelectProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title?: string;
  value?: UserVO[] | UserVO;
  data?: string | number | Array<string | number>;
  userIds?: string | number | Array<string | number>;
  multiple?: boolean;
  onChange?: (value: UserVO[] | UserVO | undefined) => void;
  onConfirm?: (value: UserVO[]) => void;
}

function normalizeIds(data: UserSelectProps['data']) {
  if (data === '' || data === null || typeof data === 'undefined') {
    return [];
  }
  if (Array.isArray(data)) {
    return data.map(item => String(item));
  }
  return String(data).split(',').filter(Boolean);
}

export default function UserSelect({
  open,
  onOpenChange,
  title = '用户选择',
  value,
  data,
  userIds,
  multiple = true,
  onChange,
  onConfirm
}: UserSelectProps) {
  const [form] = Form.useForm<UserQuery>();
  const dicts = useDict('sys_normal_disable');
  const [users, setUsers] = useState<UserVO[]>([]);
  const [total, setTotal] = useState(0);
  const [query, setQuery] = useState<UserQuery>({ pageNum: 1, pageSize: 10 });
  const [selectedUsers, setSelectedUsers] = useState<UserVO[]>([]);
  const selectedRowKeys = selectedUsers.map(item => item.userId).filter(Boolean) as React.Key[];
  const { loading, runAsync: loadUserPage } = useRequest(
    async (nextQuery: UserQuery) => {
      const params = { ...nextQuery };
      if (userIds) {
        params.userIds = Array.isArray(userIds) ? userIds.join(',') : String(userIds);
      }
      const res = await listUser(params);
      return res.data;
    },
    {
      manual: true,
      onSuccess: data => {
        setUsers(data.rows || []);
        setTotal(data.total || 0);
      }
    }
  );

  const loadUsers = async (nextQuery = query) => {
    await loadUserPage(nextQuery);
  };

  const initSelectedUsers = async () => {
    if (value) {
      setSelectedUsers(Array.isArray(value) ? value : [value]);
      return;
    }
    const ids = normalizeIds(data);
    if (!ids.length) {
      setSelectedUsers([]);
      return;
    }
    const res = await optionSelect(ids);
    setSelectedUsers(res.data || []);
  };

  // biome-ignore lint/correctness/useExhaustiveDependencies: modal initialization is intentionally tied to open state.
  useEffect(() => {
    if (!open) {
      form.resetFields();
      setQuery({ pageNum: 1, pageSize: 10 });
      setUsers([]);
      setTotal(0);
      setSelectedUsers([]);
      return;
    }

    loadUsers({ pageNum: 1, pageSize: 10 }).then(initSelectedUsers);
  }, [open]);

  const handleQuery = async () => {
    const values = form.getFieldsValue();
    const nextQuery = { ...query, ...values, pageNum: 1 };
    setQuery(nextQuery);
    await loadUsers(nextQuery);
  };

  const resetQuery = async () => {
    form.resetFields();
    const nextQuery = { pageNum: 1, pageSize: query.pageSize };
    setQuery(nextQuery);
    await loadUsers(nextQuery);
  };

  const updateSelectedUsers = (keys: React.Key[], rows: UserVO[]) => {
    const userMap = new Map<string, UserVO>();
    selectedUsers.forEach(user => {
      if (user.userId) {
        userMap.set(String(user.userId), user);
      }
    });
    users.forEach(user => {
      if (user.userId) {
        userMap.set(String(user.userId), user);
      }
    });
    rows.forEach(user => {
      if (user.userId) {
        userMap.set(String(user.userId), user);
      }
    });
    const nextUsers = keys.map(key => userMap.get(String(key))).filter(Boolean) as UserVO[];
    setSelectedUsers(multiple ? nextUsers : nextUsers.slice(0, 1));
  };

  const confirm = () => {
    onChange?.(multiple ? selectedUsers : selectedUsers[0]);
    onConfirm?.(selectedUsers);
    onOpenChange(false);
  };

  const columns: ColumnsType<UserVO> = [
    { title: '用户编号', dataIndex: 'userId', width: 112 },
    { title: '用户名称', dataIndex: 'userName' },
    { title: '用户昵称', dataIndex: 'nickName' },
    { title: '手机号码', dataIndex: 'phoneNumber', width: 130 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: value => <DictTag options={dicts.sys_normal_disable} value={value} />
    },
    { title: '创建时间', dataIndex: 'createTime', width: 170 }
  ];

  return (
    <Modal title={title} open={open} width="80%" onOk={confirm} onCancel={() => onOpenChange(false)} forceRender>
      <div className="user-select-main-react">
        <Form form={form} layout="inline" className="user-select-search-react">
          <Form.Item name="userName" label="用户名称">
            <Input allowClear placeholder="请输入用户名称" onPressEnter={handleQuery} />
          </Form.Item>
          <Form.Item name="phoneNumber" label="手机号码">
            <Input allowClear placeholder="请输入手机号码" onPressEnter={handleQuery} />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" icon={<SearchOutlined />} onClick={handleQuery}>
                搜索
              </Button>
              <Button onClick={resetQuery}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
        {multiple && selectedUsers.length ? (
          <Space size={4} wrap className="user-select-tags-react">
            {selectedUsers.map(user => (
              <Tag
                key={user.userId}
                closable
                onClose={() => setSelectedUsers(prev => prev.filter(item => item.userId !== user.userId))}
              >
                {user.nickName || user.userName}
              </Tag>
            ))}
          </Space>
        ) : null}
        <Table<UserVO>
          rowKey="userId"
          size="small"
          loading={loading}
          columns={columns}
          dataSource={users}
          scroll={{ x: 922, y: 360 }}
          rowSelection={{
            type: multiple ? 'checkbox' : 'radio',
            selectedRowKeys,
            preserveSelectedRowKeys: true,
            onChange: (keys, rows) => updateSelectedUsers(keys, rows)
          }}
          pagination={{
            current: query.pageNum,
            pageSize: query.pageSize,
            total,
            showSizeChanger: true,
            onChange: async (pageNum, pageSize) => {
              const nextQuery = { ...query, pageNum, pageSize };
              setQuery(nextQuery);
              await loadUsers(nextQuery);
            }
          }}
        />
      </div>
    </Modal>
  );
}
