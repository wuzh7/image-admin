import { AuditOutlined, CloudUploadOutlined } from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { Button, Col, Empty, Flex, Input, List, message, Popconfirm, Row, Space, Statistic, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { getImageCategoryOptions, listApprovedImages, listImageBatches, listImageDuplicateTodos, completeImageDuplicateTodo, listImageReplacementLogs } from '@/api/image';
import type { BatchSummary, ImageDuplicateTodo, ImageReplacementLog } from '@/api/image/types';
import { listRenderLogs, type StoreRenderLog } from '@/api/store';
import { listRecentOperationLogs, type OperationLog } from '@/api/operationLog';
import { useUserStore } from '@/stores/userStore';
import { hasPermi, hasRole } from '@/utils/permission';
import { formatActivityTime, formatOperationActivity } from '@/utils/activity';
import './index.less';

interface WorkbenchData {
  approvedTotal?: number;
  batchTotal?: number;
  reviewTotal?: number;
  categoryTotal?: number;
  recentOperations: OperationLog[];
  reviewBatches: BatchSummary[];
  duplicateTodos: ImageDuplicateTodo[];
  replacementLogs: ImageReplacementLog[];
  renderLogs: StoreRenderLog[];
}

const initialData: WorkbenchData = {
  recentOperations: [],
  reviewBatches: [],
  duplicateTodos: [],
  replacementLogs: [],
  renderLogs: []
};

export default function Dashboard() {
  const userInfo = useUserStore(state => state.userInfo);
  const [data, setData] = useState<WorkbenchData>(initialData);
  const [userName, setUserName] = useState('');
  const [selectedUserName, setSelectedUserName] = useState('');
  const isAdmin = hasRole(userInfo, ['superadmin']);
  const today = new Date().toLocaleDateString('sv-SE');
  const [loading, setLoading] = useState(true);
  const canUpload = hasPermi(userInfo, ['image:dedup:upload']);
  const canQuery = hasPermi(userInfo, ['image:dedup:query']);
  const canList = hasPermi(userInfo, ['image:dedup:list']);
  const canReview = hasPermi(userInfo, ['image:dedup:confirm']);

  const loadWorkbench = useCallback(async () => {
    setLoading(true);
    let failed = false;
    const safe = async <T,>(request: Promise<T> | undefined) => {
      if (!request) return undefined;
      try {
        return await request;
      } catch {
        failed = true;
        return undefined;
      }
    };
    const [approved, batches, reviews, categories, operations, todos, replacements, renderLogs] = await Promise.all([
      safe(canList ? listApprovedImages({ pageNum: 1, pageSize: 1, userName: selectedUserName || undefined }) : undefined),
      safe(canQuery ? listImageBatches({ pageNum: 1, pageSize: 6, userName: selectedUserName || undefined }) : undefined),
      safe(canQuery ? listImageBatches({ pageNum: 1, pageSize: 100, status: 'WAIT_REVIEW', userName: selectedUserName || undefined }) : undefined),
      safe(getImageCategoryOptions()),
      safe(isAdmin ? listRecentOperationLogs(selectedUserName || undefined) : undefined),
      safe(isAdmin ? listImageDuplicateTodos(selectedUserName || undefined) : undefined),
      safe(isAdmin ? listImageReplacementLogs(selectedUserName || undefined) : undefined),
      safe(isAdmin ? listRenderLogs({ userName: selectedUserName || undefined, pageNum: 1, pageSize: 20 }) : undefined)
    ]);
    setData({
      approvedTotal: approved?.data.total,
      batchTotal: batches?.data.total,
      reviewTotal: reviews?.data.total,
      categoryTotal: categories?.data.length,
      recentOperations: operations?.data.rows || [],
      reviewBatches: reviews?.data.rows || [],
      duplicateTodos: todos?.data || [],
      replacementLogs: replacements?.data || [],
      renderLogs: renderLogs?.data.records || []
    });
    if (failed) message.warning('部分工作台数据加载失败');
    setLoading(false);
  }, [canList, canQuery, isAdmin, selectedUserName]);

  useEffect(() => {
    void loadWorkbench();
  }, [loadWorkbench]);

  const hour = new Date().getHours();
  const greeting = hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好';
  const recentActivity = [
    ...data.recentOperations.map(operation => ({ kind: 'operation' as const, at: operation.operTime, operation })),
    ...data.renderLogs.map(render => ({ kind: 'render' as const, at: render.renderedAt, render })),
  ].sort((a, b) => new Date(b.at?.replace(' ', 'T') || 0).getTime() - new Date(a.at?.replace(' ', 'T') || 0).getTime()).slice(0, 20);

  return (
    <PageContainer title="工作台" loading={loading}>
      <div style={{ maxWidth: 1280, margin: '0 auto' }}>
        <Space direction="vertical" size={18} style={{ width: '100%' }}>
          <Flex justify="space-between" align="center" gap={16} wrap>
            <div>
              <Typography.Title level={3} style={{ margin: 0 }}>
                {greeting}，{userInfo?.user.userName || '图片管理员'}
              </Typography.Title>
              <Typography.Text type="secondary">
                {typeof data.reviewTotal === 'number'
                  ? `今天有 ${data.reviewTotal} 个图片批次需要处理。`
                  : '待处理任务数据暂不可用。'}
              </Typography.Text>
            </div>
            {canUpload && (
              <Button type="primary" icon={<CloudUploadOutlined />} onClick={() => history.push('/image/upload')}>
                开始导入
              </Button>
            )}
          </Flex>

          {isAdmin && <Input.Search allowClear placeholder="按用户名称筛选待办与操作日志" aria-label="工作台用户名筛选" value={userName}
            onChange={event => setUserName(event.target.value)} onSearch={value => setSelectedUserName(value.trim())} enterButton="筛选" style={{ maxWidth: 480 }} />}
          <Row gutter={[16, 16]}>
            <Col xs={24} sm={12} xl={6}>
              <ProCard><Statistic title="正式图库图片" value={data.approvedTotal ?? '-'} /></ProCard>
            </Col>
            <Col xs={24} sm={12} xl={6}>
              <ProCard><Statistic title="待人工审核" value={data.reviewTotal ?? '-'} /></ProCard>
            </Col>
            <Col xs={24} sm={12} xl={6}>
              <ProCard><Statistic title="累计上传批次" value={data.batchTotal ?? '-'} /></ProCard>
            </Col>
            <Col xs={24} sm={12} xl={6}>
              <ProCard><Statistic title="启用图片类目" value={data.categoryTotal ?? '-'} /></ProCard>
            </Col>
          </Row>
          <Row gutter={[16, 16]} align="stretch">
            <Col xs={24} xl={isAdmin ? 15 : 24}>
              <ProCard
                title="待处理任务"
                extra={canReview && <Button type="link" onClick={() => history.push('/image/review')}>查看全部</Button>}
                style={{ height: '100%' }}
              >
                {data.reviewBatches.length ? (
                  <List
                    dataSource={data.reviewBatches}
                    renderItem={batch => (
                      <List.Item
                        actions={canReview ? [<Button key="review" onClick={() => history.push(`/image/review?batchId=${batch.id}`)}>去审核</Button>] : undefined}
                      >
                        <List.Item.Meta
                          avatar={<AuditOutlined style={{ color: '#d78212', fontSize: 20 }} />}
                          title={`批次 ${String(batch.id)} · ${batch.categoryName}`}
                          description={`${batch.processedCount}/${batch.totalCount} 张已处理，检测到 ${batch.duplicateCount} 张相似图片 · ${batch.createTime || '-'}`}
                        />
                        <Tag color={batch.createTime && batch.createTime.slice(0, 10) < today ? 'error' : 'warning'}>{batch.createTime && batch.createTime.slice(0, 10) < today ? '历史待办，今日必须完成' : '待审核'}</Tag>
                      </List.Item>
                    )}
                  />
                ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待处理任务" />}
                {isAdmin && data.duplicateTodos.length > 0 && <List dataSource={data.duplicateTodos} renderItem={todo => (
                  <List.Item actions={[
                    <Button key="view" onClick={() => history.push(`/image/library?imageId=${todo.imageId}`)}>查看图片</Button>,
                    <Button key="matched" onClick={() => history.push(`/image/library?imageId=${todo.matchedImageId}`)}>查看相似图</Button>,
                    <Popconfirm key="complete" title="确认已检查并处理这组相似图片？" onConfirm={async () => { await completeImageDuplicateTodo(todo.id); await loadWorkbench(); }}><Button>已处理</Button></Popconfirm>
                  ]}>
                    <List.Item.Meta title={`每日复查：图片 ${todo.imageId} 与 ${todo.matchedImageId}`} description={`${todo.userName} / ${todo.matchedUserName} · 相似度 ${(Number(todo.similarity) * 100).toFixed(2)}% · ${todo.scanDate}`} />
                    <Tag color="error">{todo.createTime.slice(0, 10) < today ? '历史待办，今日必须完成' : '相似图片待处理'}</Tag>
                  </List.Item>
                )} /> }
              </ProCard>
            </Col>
            {isAdmin && <Col xs={24} xl={9}>
              <ProCard
                title="最近动态"
                style={{ height: '100%' }}
              >
                {recentActivity.length ? (
                  <List
                    dataSource={recentActivity}
                    pagination={{ pageSize: 10, hideOnSinglePage: true }}
                    renderItem={entry => {
                      const activity = entry.kind === 'operation' ? formatOperationActivity(entry.operation) : null;
                      const repeated = entry.kind === 'render' && entry.render.renderUseCount > 1;
                      const title = entry.kind === 'render' ? `${entry.render.userName}渲染了${entry.render.imageName || `图片 ${entry.render.imageId}`}` : activity!.title;
                      const summary = entry.kind === 'render' ? `图片 ID ${entry.render.imageId} · 渲染 ${entry.render.renderUseCount} 次${repeated ? ' · 重复渲染' : ''}` : activity!.summary;
                      return (
                        <List.Item>
                          <div className="workbench-activity-item">
                            <Typography.Text strong className={`workbench-activity-title${activity?.failed || repeated ? ' is-failed' : ''}`}>
                              {title}
                            </Typography.Text>
                            <Typography.Text className="workbench-activity-summary" title={entry.at}>
                              {summary} · {formatActivityTime(entry.at)}
                            </Typography.Text>
                          </div>
                        </List.Item>
                      );
                    }}
                    className="workbench-activity-list"
                  />
                ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无最近动态" />}
              </ProCard>
            </Col>}
          </Row>
          {isAdmin && data.replacementLogs.length > 0 && <ProCard title="图片替换日志">
            <List dataSource={data.replacementLogs} pagination={{ pageSize: 5, hideOnSinglePage: true }} renderItem={entry => (
              <List.Item>
                <Space direction="vertical">
                  <Typography.Text>{entry.userName} · {entry.createTime} · 旧图 {entry.oldImageId} → <Button type="link" onClick={() => history.push(`/image/library?imageId=${entry.newImageId}`)}>新图 {entry.newImageId}</Button></Typography.Text>
                  <Typography.Text type="secondary" copyable>旧图 OSS：{entry.oldStorageKey}</Typography.Text>
                </Space>
              </List.Item>
            )} />
          </ProCard>}
        </Space>
      </div>
    </PageContainer>
  );
}
