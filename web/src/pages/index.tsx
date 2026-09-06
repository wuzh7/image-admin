import { AuditOutlined, CloudUploadOutlined } from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { Button, Col, Empty, Flex, List, message, Row, Space, Statistic, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { getImageCategoryOptions, listApprovedImages, listImageBatches } from '@/api/image';
import type { BatchSummary } from '@/api/image/types';
import { listRecentOperationLogs, type OperationLog } from '@/api/operationLog';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';
import { formatActivityTime, formatOperationActivity } from '@/utils/activity';
import './index.less';

interface WorkbenchData {
  approvedTotal?: number;
  batchTotal?: number;
  reviewTotal?: number;
  categoryTotal?: number;
  recentOperations: OperationLog[];
  reviewBatches: BatchSummary[];
}

const initialData: WorkbenchData = {
  recentOperations: [],
  reviewBatches: []
};

export default function Dashboard() {
  const userInfo = useUserStore(state => state.userInfo);
  const [data, setData] = useState<WorkbenchData>(initialData);
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
    const [approved, batches, reviews, categories, operations] = await Promise.all([
      safe(canList ? listApprovedImages({ pageNum: 1, pageSize: 1 }) : undefined),
      safe(canQuery ? listImageBatches({ pageNum: 1, pageSize: 6 }) : undefined),
      safe(canQuery ? listImageBatches({ pageNum: 1, pageSize: 4, status: 'WAIT_REVIEW' }) : undefined),
      safe(getImageCategoryOptions()),
      safe(listRecentOperationLogs())
    ]);
    setData({
      approvedTotal: approved?.data.total,
      batchTotal: batches?.data.total,
      reviewTotal: reviews?.data.total,
      categoryTotal: categories?.data.length,
      recentOperations: operations?.data.rows || [],
      reviewBatches: reviews?.data.rows || []
    });
    if (failed) message.warning('部分工作台数据加载失败');
    setLoading(false);
  }, [canList, canQuery]);

  useEffect(() => {
    void loadWorkbench();
  }, [loadWorkbench]);

  const hour = new Date().getHours();
  const greeting = hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好';

  return (
    <PageContainer title="工作台" loading={loading}>
      <div style={{ maxWidth: 1280, margin: '0 auto' }}>
        <Space direction="vertical" size={18} style={{ width: '100%' }}>
          <Flex justify="space-between" align="center" gap={16} wrap>
            <div>
              <Typography.Title level={3} style={{ margin: 0 }}>
                {greeting}，{userInfo?.user.nickName || userInfo?.user.userName || '图片管理员'}
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
            <Col xs={24} xl={15}>
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
                        actions={canReview ? [<Button key="review" onClick={() => history.push('/image/review')}>去审核</Button>] : undefined}
                      >
                        <List.Item.Meta
                          avatar={<AuditOutlined style={{ color: '#d78212', fontSize: 20 }} />}
                          title={`批次 ${String(batch.id)} · ${batch.categoryName}`}
                          description={`${batch.processedCount}/${batch.totalCount} 张已处理，检测到 ${batch.duplicateCount} 张相似图片 · ${batch.createTime || '-'}`}
                        />
                        <Tag color="warning">待审核</Tag>
                      </List.Item>
                    )}
                  />
                ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待处理任务" />}
              </ProCard>
            </Col>
            <Col xs={24} xl={9}>
              <ProCard
                title="最近动态"
                style={{ height: '100%' }}
              >
                {data.recentOperations.length ? (
                  <List<OperationLog>
                    dataSource={data.recentOperations}
                    renderItem={operation => {
                      const activity = formatOperationActivity(operation);
                      return (
                        <List.Item>
                          <div className="workbench-activity-item">
                            <Typography.Text strong className={`workbench-activity-title${activity.failed ? ' is-failed' : ''}`}>
                              {activity.title}
                            </Typography.Text>
                            <Typography.Text className="workbench-activity-summary" title={operation.operTime}>
                              {activity.summary} · {formatActivityTime(operation.operTime)}
                            </Typography.Text>
                          </div>
                        </List.Item>
                      );
                    }}
                    className="workbench-activity-list"
                  />
                ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无最近动态" />}
              </ProCard>
            </Col>
          </Row>
        </Space>
      </div>
    </PageContainer>
  );
}
