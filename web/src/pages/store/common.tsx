import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Alert, Button, Empty, Flex, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { getImageCategoryOptions } from '@/api/image';
import type { ImageCategory, ImageId } from '@/api/image/types';
import type { StoreTemplateGroup } from '@/api/store';
import StoreImage from './StoreImage';

export function StorePage({ title, children, extra }: { title: string; children: ReactNode; extra?: ReactNode }) {
  return <PageContainer title={title} extra={extra}>{children}</PageContainer>;
}

export function useStoreCategories() {
  const [categories, setCategories] = useState<ImageCategory[]>([]);
  useEffect(() => { void getImageCategoryOptions().then(response => setCategories(response.data || [])); }, []);
  return categories;
}

export function CategorySelect({ value, onChange, categories }: { value?: ImageId; onChange: (value?: ImageId) => void; categories: ImageCategory[] }) {
  return <Select allowClear placeholder="全部类目" value={value} options={categories.map(item => ({ value: item.id, label: item.name }))} onChange={onChange} style={{ minWidth: 180 }} />;
}

export function GroupCards({ groups, onDelete, onUse, onEdit }: { groups: StoreTemplateGroup[]; onDelete: (group: StoreTemplateGroup) => void; onUse: (group: StoreTemplateGroup) => void; onEdit: (group: StoreTemplateGroup) => void }) {
  if (!groups.length) return <Empty description="暂无模板组" />;
  return <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(280px,1fr))', gap: 16, maxHeight: 'calc(100vh - 280px)', overflow: 'auto', padding: 4 }}>
    {groups.map(group => <ProCard key={String(group.id)} title={group.name} extra={<Tag>{group.templates.length} 张模板</Tag>}>
      <Typography.Text type="secondary">类目 {String(group.categoryId)}</Typography.Text>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 8, margin: '12px 0' }}>
        {group.templates.map(template => <StoreImage key={String(template.id)} url={template.imageUrl} alt={template.name} style={{ width: '100%', aspectRatio: '1', objectFit: 'cover', borderRadius: 4 }} />)}
      </div>
      <Flex justify="space-between"><Button type="link" disabled={group.status !== 'ACTIVE'} onClick={() => onUse(group)}>使用此组</Button><Button type="link" onClick={() => onEdit(group)}>编辑</Button><Popconfirm title="删除这个模板组？" onConfirm={() => onDelete(group)}><Button danger type="link">删除</Button></Popconfirm></Flex>
    </ProCard>)}
  </div>;
}

export function ApiError({ error }: { error?: unknown }) {
  return error ? <Alert type="error" showIcon message={error instanceof Error ? error.message : '请求失败，请稍后重试'} /> : null;
}

export function PageToolbar({ children }: { children: ReactNode }) {
  return <Space wrap style={{ marginBottom: 16 }}>{children}</Space>;
}
