import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Button, Form, Input, InputNumber, message, Modal, Popconfirm, Space, Switch, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  createImageCategory,
  deleteImageCategory,
  listImageCategories,
  updateImageCategory
} from '@/api/image';
import type { ImageCategory, ImageId } from '@/api/image/types';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';

interface CategoryForm {
  name: string;
  sortOrder: number;
  enabled: boolean;
}

export default function ImageCategoryPage() {
  const [form] = Form.useForm<CategoryForm>();
  const [categories, setCategories] = useState<ImageCategory[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingId, setEditingId] = useState<ImageId>();
  const userInfo = useUserStore(state => state.userInfo);
  const canAdd = hasPermi(userInfo, ['image:category:add']);
  const canEdit = hasPermi(userInfo, ['image:category:edit']);
  const canRemove = hasPermi(userInfo, ['image:category:remove']);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const response = await listImageCategories();
      setCategories(Array.from(response.data || []));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const openCreate = () => {
    setEditingId(undefined);
    form.setFieldsValue({ name: '', sortOrder: categories.length + 1, enabled: true });
    setModalOpen(true);
  };

  const openEdit = (category: ImageCategory) => {
    setEditingId(category.id);
    form.setFieldsValue({
      name: category.name,
      sortOrder: category.sortOrder,
      enabled: category.status === '0'
    });
    setModalOpen(true);
  };

  const save = async () => {
    const values = await form.validateFields();
    const data = {
      name: values.name.trim(),
      sortOrder: values.sortOrder,
      status: values.enabled ? ('0' as const) : ('1' as const)
    };
    setSaving(true);
    try {
      if (editingId == null) {
        await createImageCategory(data);
        message.success('类目已新增');
      } else {
        await updateImageCategory(editingId, data);
        message.success('类目已更新');
      }
      setModalOpen(false);
      await load();
    } finally {
      setSaving(false);
    }
  };

  const columns: TableColumnsType<ImageCategory> = [
    { title: '类目名称', dataIndex: 'name' },
    { title: '排序', dataIndex: 'sortOrder', width: 100 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: status => status === '0' ? <Tag color="success">启用</Tag> : <Tag>停用</Tag>
    },
    {
      title: '操作',
      width: 180,
      render: (_, category) => (
        <Space size={0}>
          {canEdit && (
            <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(category)}>
              修改
            </Button>
          )}
          {canRemove && (
            <Popconfirm
              title="确认删除该类目？"
              description="已被图片使用的类目不能删除，可以改为停用。"
              onConfirm={async () => {
                await deleteImageCategory(category.id);
                message.success('类目已删除');
                await load();
              }}
            >
              <Button danger type="link" icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
          )}
        </Space>
      )
    }
  ];

  return (
    <PageContainer title="类目维护">
      <ProCard>
        <Space style={{ marginBottom: 16 }}>
          {canAdd && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增类目</Button>}
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
        </Space>
        <Table<ImageCategory>
          rowKey={row => String(row.id)}
          columns={columns}
          dataSource={categories}
          loading={loading}
          pagination={false}
        />
      </ProCard>
      <Modal
        title={editingId == null ? '新增类目' : '修改类目'}
        open={modalOpen}
        confirmLoading={saving}
        onOk={() => void save()}
        onCancel={() => setModalOpen(false)}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item label="类目名称" name="name" rules={[{ required: true, whitespace: true, message: '请输入类目名称' }, { max: 50 }]}>
            <Input placeholder="例如：家居收纳" />
          </Form.Item>
          <Form.Item label="排序" name="sortOrder" rules={[{ required: true, message: '请输入排序' }]}>
            <InputNumber min={0} max={9999} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
}
