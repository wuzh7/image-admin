import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Button, Form, Input, message, Modal, Space, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  createImageCategory,
  listImageCategories
} from '@/api/image';
import type { ImageCategory } from '@/api/image/types';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';

interface CategoryForm {
  name: string;
}

export default function ImageCategoryPage() {
  const [form] = Form.useForm<CategoryForm>();
  const [categories, setCategories] = useState<ImageCategory[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const userInfo = useUserStore(state => state.userInfo);
  const canAdd = hasPermi(userInfo, ['image:category:add']);

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
    form.setFieldsValue({ name: '' });
    setModalOpen(true);
  };

  const save = async () => {
    const values = await form.validateFields();
    const data = {
      name: values.name.trim(),
      status: '0' as const
    };
    setSaving(true);
    try {
      await createImageCategory(data);
      message.success('类目已新增');
      setModalOpen(false);
      await load();
    } finally {
      setSaving(false);
    }
  };

  const columns: TableColumnsType<ImageCategory> = [
    { title: '类目名称', dataIndex: 'name' },
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
        title="新增类目"
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
        </Form>
      </Modal>
    </PageContainer>
  );
}
