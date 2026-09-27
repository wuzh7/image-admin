import { UploadOutlined } from '@ant-design/icons';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { Button, Upload, message } from 'antd';
import { useRef, useState } from 'react';
import { importTroCases, listTroCases, type TroCase, type TroQuery } from '@/api/tro';
import { toPageQuery, toTableData } from '@/utils/wuzh';

export default function TroPage() {
  const actionRef = useRef<ActionType>(null);
  const [saving, setSaving] = useState(false);

  const columns: ProColumns<TroCase>[] = [
    { title: '案号', dataIndex: 'caseNoFormat', width: 140 },
    { title: '原告', dataIndex: 'plaintiff', width: 200, ellipsis: true },
    { title: '案件名称', dataIndex: 'caseName', width: 260, ellipsis: true },
    { title: '立案日期', dataIndex: 'dateFiled', width: 120, search: false },
    { title: '法院', dataIndex: 'court', width: 190, search: false, ellipsis: true },
    { title: '法官', dataIndex: 'judge', width: 150, search: false, ellipsis: true },
    { title: '描述', dataIndex: 'description', width: 140, search: false, ellipsis: true },
    { title: '案件类型', dataIndex: 'natureOfSuit', width: 140, search: false, ellipsis: true },
    { title: '案由', dataIndex: 'cause', width: 190, search: false, ellipsis: true },
    { title: '原告中文名', dataIndex: 'plaintiffCn', width: 170, search: false, ellipsis: true },
    {
      title: '原告别名', dataIndex: 'plaintiffNames', width: 220, search: false, ellipsis: true,
      renderText: (names: string[] | undefined) => names?.join('、') || '-'
    },
    { title: '律师', dataIndex: 'lawyer', width: 160, search: false, ellipsis: true },
    { title: '律所', dataIndex: 'lawFirms', width: 160, search: false, ellipsis: true }
  ];

  const submit = async (file: File) => {
    setSaving(true);
    try {
      const response = await importTroCases(file);
      message.success(`已处理 ${response.data} 条，重复案号和原告已覆盖`);
      actionRef.current?.reload();
    } finally {
      setSaving(false);
    }
  };

  return (
    <PageContainer title="TRO信息">
      <ProTable<TroCase, TroQuery>
        rowKey="id"
        actionRef={actionRef}
        columns={columns}
        scroll={{ x: 2400 }}
        search={{ labelWidth: 90 }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        request={async params => toTableData(await listTroCases(toPageQuery(params)))}
        toolBarRender={() => [
          <Upload key="upload" accept=".json,application/json" showUploadList={false}
            beforeUpload={file => { void submit(file); return false; }}>
            <Button type="primary" icon={<UploadOutlined />} loading={saving}>上传 JSON 新增</Button>
          </Upload>
        ]}
      />
    </PageContainer>
  );
}
