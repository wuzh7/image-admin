import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { Button, Empty, Modal, Pagination, Progress, Select, Space, Tag, message } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { confirmRenderDownload, deleteRenderResults, getRenderArchive, getRenderArchiveUrl, listRenderBatches, listRenderGroups, retryRenderBatch, stopRenderBatch, type StoreId, type StoreRenderBatch, type StoreRenderGroup } from '@/api/store';
import { isHandledRequestError } from '@/api/request';
import AuthorizedImage from '@/components/common/AuthorizedImage';
import StoreImage from '../StoreImage';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';

function BatchItems({ batchId }: { batchId: StoreId }) {
  const [groups, setGroups] = useState<StoreRenderGroup[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [preview, setPreview] = useState<string>();
  useEffect(() => { void listRenderGroups({ batchId, pageNum: page, pageSize: 6 }).then(response => { setGroups(response.data?.records || []); setTotal(response.data?.total || 0); }); }, [batchId, page]);
  if (!groups.length) return <Empty description="暂无渲染明细" />;
  return <div style={{ display: 'grid', gap: 16 }}>
    {groups.map(group => <div key={String(group.imageId)} style={{ display: 'flex', gap: 16, padding: 16, border: '1px solid #f0f0f0', borderRadius: 8, overflowX: 'auto' }}>
      <div style={{ flex: '0 0 160px', borderRight: '1px solid #f0f0f0', paddingRight: 16 }}>
        <AuthorizedImage url={`/images/${group.imageId}/content?thumbnail=false`} width={144} height={144} alt="原图" />
        <div>原图 · {group.originalName || String(group.imageId)}</div>
      </div>
      {group.items.map((item, index) => <div key={String(item.id)} style={{ flex: '0 0 150px' }}>
        <div style={{ width: 144, height: 144, cursor: item.resultUrl ? 'zoom-in' : 'default' }} onClick={() => item.resultUrl && setPreview(item.resultUrl)}>
          <StoreImage url={item.resultUrl} alt={`模板图 ${index + 1}`} style={{ width: 144, height: 144, objectFit: 'contain' }} />
        </div>
        <div>{index === 0 ? '主图' : `附图 ${index}`}</div>
        {item.errorMessage && <div>系统错误，请稍后重试</div>}
      </div>)}
    </div>)}
    <Pagination current={page} pageSize={6} total={total} onChange={setPage} />
    <Modal open={!!preview} footer={null} onCancel={() => setPreview(undefined)} width={900} destroyOnHidden>
      <StoreImage url={preview} alt="渲染大图" style={{ width: '100%', maxHeight: '80vh', objectFit: 'contain' }} />
    </Modal>
  </div>;
}

export default function RenderResultPage() {
  const userInfo = useUserStore(state => state.userInfo);
  const canRetry = hasPermi(userInfo, ['store:render:retry']);
  const canStop = hasPermi(userInfo, ['store:render:add']);
  const canDownload = hasPermi(userInfo, ['store:render:download']);
  const actionRef = useRef<ActionType>(null);
  const [nameMode, setNameMode] = useState<'ORIGINAL_NAME' | 'ENGLISH_KEYWORDS'>('ORIGINAL_NAME');
  const [downloading, setDownloading] = useState(false);
  const download = async (row: StoreRenderBatch) => {
    setDownloading(true);
    try {
      let url = (await getRenderArchiveUrl(row.id, nameMode)).data;
      if (!url) {
        const archive = await getRenderArchive(row.id, nameMode);
        const signature = new Uint8Array(await archive.slice(0, 2).arrayBuffer());
        if (signature[0] !== 0x50 || signature[1] !== 0x4b) throw new Error('ZIP 内容无效');
        url = URL.createObjectURL(archive);
        window.setTimeout(() => URL.revokeObjectURL(url), 60000);
      }
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `render-${row.id}.zip`; anchor.click();
      try { await confirmRenderDownload(row.id); }
      catch { message.warning('ZIP 下载已开始，但下载记录未保存；请重试下载以启用三天后自动清理'); }
    } catch (error) { if (!isHandledRequestError(error)) message.error('ZIP 下载失败，请稍后重试'); }
    finally { setDownloading(false); }
  };
  const columns: ProColumns<StoreRenderBatch>[] = [
    { title: '批次', dataIndex: 'id' },
    { title: '状态', dataIndex: 'status', render: value => <Tag>{String(value)}</Tag> },
    { title: '进度', render: (_, row) => <Progress percent={row.totalCount ? Math.round(((row.completedCount + row.skippedCount) / row.totalCount) * 100) : 0} size="small" /> },
    { title: '明细', render: (_, row) => String(row.completedCount + row.skippedCount) + '/' + String(row.totalCount) + '，失败 ' + String(row.failedCount) },
    { title: '操作', render: (_, row) => <Space><Button icon={<ReloadOutlined />} onClick={() => actionRef.current?.reload()}>刷新</Button>{canStop && ['CREATED', 'RUNNING'].includes(row.status) && <Button danger onClick={() => Modal.confirm({ title: '停止当前渲染批次？', content: '正在渲染的图片完成后停止，其余图片不再开始。', onOk: async () => { await stopRenderBatch(row.id); actionRef.current?.reload(); } })}>停止</Button>}{canRetry && <Button disabled={!row.failedCount || ['STOPPING', 'STOPPED'].includes(row.status)} onClick={async () => { await retryRenderBatch(row.id); message.success('失败明细已重新排队'); actionRef.current?.reload(); }}>重试失败</Button>}{canDownload && <Button icon={<DownloadOutlined />} loading={downloading} disabled={!['COMPLETED', 'PARTIAL_FAILURE', 'STOPPED'].includes(row.status) || !(row.completedCount + row.skippedCount)} onClick={() => Modal.confirm({ title: '确认下载渲染图？', content: `将按“${nameMode === 'ORIGINAL_NAME' ? '原文件名' : '英文关键词'}”命名 ZIP 内的图片。`, okText: '确认下载', cancelText: '返回选择', onOk: () => download(row) })}>下载 ZIP</Button>}{canStop && ['COMPLETED', 'PARTIAL_FAILURE', 'FAILED', 'STOPPED'].includes(row.status) && <Button danger onClick={() => Modal.confirm({ title: '删除该批次的渲染结果？', content: '删除后无法重新下载，原图和渲染日志保留。', okText: '删除结果', okButtonProps: { danger: true }, onOk: async () => { await deleteRenderResults(row.id); actionRef.current?.reload(); } })}>删除结果</Button>}</Space> }
  ];
  return <PageContainer title="渲染结果"><ProTable<StoreRenderBatch> actionRef={actionRef} rowKey={row => String(row.id)} columns={columns} request={async params => { const response = await listRenderBatches({ pageNum: params.current, pageSize: params.pageSize }); return { data: response.data?.records || [], total: response.data?.total || 0, success: true }; }} search={false} pagination={{ pageSize: 20 }} expandable={{ expandedRowRender: row => <BatchItems batchId={row.id} /> }} toolBarRender={() => [<Select key="nameMode" aria-label="图片命名方式" value={nameMode} onChange={setNameMode} style={{ width: 160 }} options={[{ label: '原文件名', value: 'ORIGINAL_NAME' }, { label: '英文关键词', value: 'ENGLISH_KEYWORDS' }]} />]} /></PageContainer>;
}
