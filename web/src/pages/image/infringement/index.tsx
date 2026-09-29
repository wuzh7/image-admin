import { CodeOutlined, EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer, ProTable, type ActionType, type ProColumns } from '@ant-design/pro-components';
import { Alert, Button, Descriptions, Drawer, message, Progress, Space, Spin, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  getInfringementCallResponse,
  listInfringementItems,
  listInfringementCallLogs,
  listInfringementTasks,
  retryFailedInfringementTask
} from '@/api/image';
import type { InfringementCallLogView, InfringementItemView, InfringementTaskSummary } from '@/api/image/types';
import { useUserStore } from '@/stores/userStore';
import { hasPermi } from '@/utils/permission';
import AuthorizedImage from '@/components/common/AuthorizedImage';
import { toPageQuery, toTableData } from '@/utils/wuzh';

const { Text } = Typography;

function formatJson(value?: string) {
  if (!value) return '-';
  try { return JSON.stringify(JSON.parse(value), null, 2); }
  catch { return value; }
}

function ProviderResponsePanel({ call }: { call: InfringementCallLogView }) {
  const [response, setResponse] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let active = true;
    setLoading(true);
    setFailed(false);
    setResponse(undefined);
    void getInfringementCallResponse(call.taskId, call.id).then(result => {
      if (active) setResponse(result.data.rawProviderResponse);
    }).catch(() => { if (active) setFailed(true); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [call.taskId, call.id]);

  if (loading) return <Spin tip="正在读取完整响应"><div style={{ minHeight: 96 }} /></Spin>;
  if (failed) return <Alert type="error" showIcon message="完整响应读取失败，请关闭详情后重试" />;
  if (!response) return <Alert type="info" showIcon message="本次调用未收到上游响应，无法展示完整响应内容。" />;

  let grounding: unknown[] = [];
  let validJson = true;
  try {
    const parsed = JSON.parse(response);
    const generated = parsed?.response ?? parsed;
    grounding = Array.isArray(generated?.candidates)
      ? generated.candidates.filter((candidate: { groundingMetadata?: unknown }) => candidate?.groundingMetadata != null)
        .map((candidate: { groundingMetadata: unknown }) => candidate.groundingMetadata)
      : [];
  } catch { validJson = false; }
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="info" showIcon message={validJson
        ? `groundingMetadata：${grounding.length ? '响应包含该字段，具体搜索记录见下方' : '响应未包含该字段'}`
        : '上游返回不是有效 JSON，以下保留原始响应内容'} />
      <div>
        <Text strong>上游完整响应 JSON</Text>
        <Typography.Paragraph copyable={{ text: response }}
          style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'monospace', maxHeight: 480, overflow: 'auto', marginTop: 8 }}>
          {formatJson(response)}
        </Typography.Paragraph>
      </div>
      {grounding.length > 0 && <div>
        <Text strong>groundingMetadata 完整数据</Text>
        <Typography.Paragraph copyable
          style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'monospace', maxHeight: 260, overflow: 'auto', marginTop: 8 }}>
          {JSON.stringify(grounding, null, 2)}
        </Typography.Paragraph>
      </div>}
    </Space>
  );
}

const taskStatus = {
  PENDING: { color: 'default', text: '等待中' },
  PROCESSING: { color: 'processing', text: '处理中' },
  COMPLETED: { color: 'success', text: '已完成' },
  PARTIAL_FAILED: { color: 'warning', text: '部分失败' },
  FAILED: { color: 'error', text: '全部失败' }
} as const;

const itemStatus = {
  PENDING: { color: 'default', text: '等待中' },
  PROCESSING: { color: 'processing', text: '检测中' },
  SUCCESS: { color: 'success', text: '检测完成' },
  FAILED: { color: 'error', text: '检测失败' }
} as const;

const riskTag = (riskLevel?: InfringementItemView['riskLevel'], analyzed = false) => {
  if (riskLevel === 'HIGH') return <Tag color="error">高风险</Tag>;
  if (riskLevel === 'NEEDS_REVIEW') return <Tag color="warning">疑似侵权</Tag>;
  if (riskLevel === 'LOW') return <Tag color="success">低风险</Tag>;
  if (analyzed) return <Tag color="blue">未评估</Tag>;
  return '-';
};

const labels = (values: string[]) => values.length ? values.map(value => <Tag key={value}>{value}</Tag>) : '-';
const modeLabel = {
  RELAY: '中转站',
  OFFICIAL_HYBRID: '官方混合',
  OFFICIAL_STANDARD: '官方 Standard',
  OFFICIAL_BATCH: '官方 Batch',
  OFFICIAL_FLEX: '官方 Standard'
} as const;

const executionModeLabel = {
  RELAY: '中转站实时',
  OFFICIAL_STANDARD: '官方 Standard 实时',
  OFFICIAL_BATCH: '官方 Batch'
} as const;

export default function ImageInfringementPage() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const [selectedTask, setSelectedTask] = useState<InfringementTaskSummary>();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selectedItem, setSelectedItem] = useState<InfringementItemView>();
  const [logTask, setLogTask] = useState<InfringementTaskSummary>();
  const [selectedCall, setSelectedCall] = useState<InfringementCallLogView>();
  const [retryingTaskId, setRetryingTaskId] = useState<InfringementTaskSummary['id']>();
  const userInfo = useUserStore(state => state.userInfo);
  const canRetry = hasPermi(userInfo, ['image:infringement:retry']);

  const taskColumns: ProColumns<InfringementTaskSummary>[] = [
    { title: '任务编号', dataIndex: 'taskNo', width: 210, search: false, copyable: true },
    {
      title: '任务类型', dataIndex: 'taskType', width: 110, search: false,
      render: (_, row) => row.taskType === 'INITIAL' ? '入库检测' : '失败批量重检'
    },
    { title: '创建人', dataIndex: 'creatorName', width: 110, search: false },
    { title: '检测模式', dataIndex: 'infringementMode', width: 110, search: false, render: (_, row) => modeLabel[row.infringementMode] },
    {
      title: '状态', dataIndex: 'status', width: 110, search: false,
      render: (_, row) => <Tag color={taskStatus[row.status].color}>{taskStatus[row.status].text}</Tag>
    },
    {
      title: '进度', dataIndex: 'completedCount', width: 190, search: false,
      render: (_, row) => (
        <Progress
          percent={row.totalCount ? Math.round(row.completedCount * 100 / row.totalCount) : 0}
          size="small"
          format={() => `${row.completedCount}/${row.totalCount}`}
        />
      )
    },
    { title: '高风险', dataIndex: 'highRiskCount', width: 80, search: false, render: value => <Text type="danger">{String(value)}</Text> },
    { title: '低风险', dataIndex: 'lowRiskCount', width: 80, search: false },
    { title: '疑似侵权', dataIndex: 'needsReviewCount', width: 95, search: false, render: value => <Text type="warning">{String(value)}</Text> },
    { title: '失败', dataIndex: 'failedCount', width: 70, search: false },
    { title: '创建时间', dataIndex: 'createTime', valueType: 'dateTime', width: 170, search: false },
    {
      title: '操作', valueType: 'option', fixed: 'right', width: 250,
      render: (_, row) => [
        <Button key="detail" type="link" icon={<EyeOutlined />} onClick={() => {
          setSelectedTask(row);
          setDrawerOpen(true);
        }}>
          明细
        </Button>,
        <Button key="calls" type="link" icon={<CodeOutlined />} onClick={() => setLogTask(row)}>
          调用日志
        </Button>,
        canRetry && <Button key="retry" type="link" icon={<ReloadOutlined />}
          disabled={!row.failedCount} loading={retryingTaskId === row.id}
          onClick={() => void retryFailed(row.id)}>
          重检失败项
        </Button>
      ]
    }
  ];

  const callColumns: ProColumns<InfringementCallLogView>[] = [
    { title: '图片名称', dataIndex: 'imageName', width: 170, search: false, ellipsis: true },
    { title: '图片ID', dataIndex: 'imageId', width: 190, copyable: true },
    {
      title: '状态', dataIndex: 'status', width: 90,
      valueEnum: { SUCCESS: { text: '成功' }, FAILED: { text: '失败' } },
      render: (_, row) => <Tag color={row.status === 'SUCCESS' ? 'success' : 'error'}>{row.status === 'SUCCESS' ? '成功' : '失败'}</Tag>
    },
    { title: '风险', dataIndex: 'riskLevel', width: 90, search: false, render: (_, row) => riskTag(row.riskLevel, row.status === 'SUCCESS') },
    { title: '调用类型', dataIndex: 'callType', width: 100, search: false,
      render: (_, row) => row.callType === 'CASE_LOOKUP' ? '案号补查' : row.status === 'SUCCESS' && !row.riskLevel ? '图片分析' : '风险检测' },
    {
      title: '执行方式', dataIndex: 'executionMode', width: 150,
      valueEnum: Object.fromEntries(Object.entries(executionModeLabel).map(([key, text]) => [key, { text }])),
      render: value => executionModeLabel[value as keyof typeof executionModeLabel] || '-'
    },
    { title: '输入Token', dataIndex: 'promptTokenCount', width: 100, search: false },
    { title: '缓存Token', dataIndex: 'cachedContentTokenCount', width: 100, search: false },
    { title: '输出Token', dataIndex: 'candidatesTokenCount', width: 100, search: false },
    { title: '思考Token', dataIndex: 'thoughtsTokenCount', width: 100, search: false },
    { title: '工具使用Token（当前为Google搜索）', dataIndex: 'toolUsePromptTokenCount', width: 220, search: false },
    { title: '总Token', dataIndex: 'totalTokenCount', width: 100, search: false },
    { title: 'Google搜索', dataIndex: 'searchUsed', width: 110, search: false, render: value => value ? '已使用' : '未使用' },
    { title: '搜索次数', dataIndex: 'searchQueryCount', width: 100, search: false },
    { title: '耗时', dataIndex: 'durationMs', width: 100, search: false, render: value => value == null ? '-' : `${value} ms` },
    { title: '完成时间', dataIndex: 'finishedAt', valueType: 'dateTime', width: 170, search: false },
    { title: '操作', valueType: 'option', fixed: 'right', width: 76, render: (_, row) => <Button type="link" onClick={() => setSelectedCall(row)}>详情</Button> }
  ];

  const itemColumns: ProColumns<InfringementItemView>[] = [
    { title: '图片', dataIndex: 'thumbnailUrl', width: 88, search: false, render: (_, row) =>
      <AuthorizedImage url={row.thumbnailUrl} alt={row.imageDescription || row.imageName} width={64} height={64} /> },
    { title: '图片名称', dataIndex: 'imageName', width: 180, search: false, ellipsis: true },
    { title: '图片ID', dataIndex: 'imageId', width: 190, search: false, copyable: true },
    {
      title: '检测状态', dataIndex: 'status', width: 110,
      valueEnum: Object.fromEntries(Object.entries(itemStatus).map(([key, value]) => [key, { text: value.text }])),
      render: (_, row) => <Tag color={itemStatus[row.status].color}>{itemStatus[row.status].text}</Tag>
    },
    {
      title: '风险等级', dataIndex: 'riskLevel', width: 105,
      valueEnum: { HIGH: { text: '高风险' }, NEEDS_REVIEW: { text: '疑似侵权' }, LOW: { text: '低风险' } },
      render: (_, row) => riskTag(row.riskLevel, row.status === 'SUCCESS' && row.promptVersion === 'relay_image_analysis_v1')
    },
    { title: '风险原因/疑点', dataIndex: 'riskReason', width: 220, search: false, ellipsis: true },
    { title: 'TRO 案号', dataIndex: 'caseNumbers', width: 180, search: false,
      render: (_, row) => labels(row.caseNumbers || []) },
    { title: '描述关键词', dataIndex: 'imageDescription', width: 240, search: false, ellipsis: true },
    { title: '调用次数', dataIndex: 'attemptCount', width: 90, search: false },
    {
      title: '失败原因', dataIndex: 'errorMessage', width: 240, search: false,
      render: value => value
        ? <Text type="danger" style={{ display: 'block' }}>系统错误，请稍后重试</Text>
        : '-'
    },
    { title: '模型', dataIndex: 'model', width: 140, search: false, ellipsis: true },
    { title: '执行方式', dataIndex: 'executionMode', width: 150, search: false, render: value => executionModeLabel[value as keyof typeof executionModeLabel] || '-' },
    { title: '完成时间', dataIndex: 'finishedAt', valueType: 'dateTime', width: 170, search: false },
    {
      title: '操作', width: 76, fixed: 'right', search: false,
      render: (_, row) => <Button type="link" onClick={() => setSelectedItem(row)}>详情</Button>
    }
  ];

  const retryFailed = async (taskId: InfringementTaskSummary['id']) => {
    setRetryingTaskId(taskId);
    try {
      const response = await retryFailedInfringementTask(taskId);
      const count = response.data.imageCount;
      message.success(count ? `已创建重检任务，共 ${count} 张图片` : '该任务没有可重检的失败图片');
      actionRef.current?.reload();
    } finally {
      setRetryingTaskId(undefined);
    }
  };

  return (
    <PageContainer title="图片 AI 分析" subTitle="只分析可见内容；历史风险结果仍可查看">
      <main>
        <ProTable<InfringementTaskSummary>
          actionRef={actionRef}
          rowKey="id"
          columns={taskColumns}
          search={false}
          scroll={{ x: 1420 }}
          pagination={{ defaultPageSize: 10, showSizeChanger: true }}
          request={async params => toTableData(await listInfringementTasks(toPageQuery(params)))}
          toolbar={{ title: '分析任务' }}
        />
      </main>

      <Drawer
        title={selectedTask ? `检测明细 · ${selectedTask.taskNo}` : '检测明细'}
        open={drawerOpen}
        width="min(1180px, 94vw)"
        onClose={() => setDrawerOpen(false)}
        destroyOnHidden
      >
        {selectedTask && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Text type="secondary">新任务只返回英文描述和中文分类，不进行风险判断。</Text>
            <ProTable<InfringementItemView>
              rowKey="id"
              columns={itemColumns}
              scroll={{ x: 1750 }}
              search={{ labelWidth: 80, defaultCollapsed: false }}
              pagination={{ defaultPageSize: 10, showSizeChanger: true }}
              request={async params => toTableData(await listInfringementItems(selectedTask.id, toPageQuery(params)))}
              toolbar={{ title: '图片结果' }}
            />
          </Space>
        )}
      </Drawer>
      <Drawer
        title={logTask ? `调用日志 · ${logTask.taskNo}` : '调用日志'}
        open={Boolean(logTask)}
        width="min(1480px, 96vw)"
        onClose={() => setLogTask(undefined)}
        destroyOnHidden
      >
        {logTask && (
          <ProTable<InfringementCallLogView>
            rowKey="id"
            columns={callColumns}
            scroll={{ x: 1900 }}
            search={{ labelWidth: 80, defaultCollapsed: false }}
            pagination={{ defaultPageSize: 20, showSizeChanger: true }}
            request={async params => toTableData(await listInfringementCallLogs(logTask.id, toPageQuery(params)))}
            toolbar={{ title: '每次 AI 调用' }}
          />
        )}
      </Drawer>
      <Drawer
        title={selectedCall ? `调用详情 · ${selectedCall.imageName || selectedCall.imageId}` : '调用详情'}
        open={Boolean(selectedCall)}
        width="min(820px, 94vw)"
        onClose={() => setSelectedCall(undefined)}
        destroyOnHidden
      >
        {selectedCall && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="请求ID">{selectedCall.requestId || '-'}</Descriptions.Item>
              <Descriptions.Item label="批次ID">{selectedCall.batchId || '-'}</Descriptions.Item>
              <Descriptions.Item label="图片ID">{selectedCall.imageId}</Descriptions.Item>
              <Descriptions.Item label="执行方式">{selectedCall.executionMode ? executionModeLabel[selectedCall.executionMode] : '-'}</Descriptions.Item>
              <Descriptions.Item label="调用类型">{selectedCall.callType === 'CASE_LOOKUP' ? '案号补查' : selectedCall.status === 'SUCCESS' && !selectedCall.riskLevel ? '图片分析' : '风险检测'}</Descriptions.Item>
              <Descriptions.Item label="Batch作业ID">{selectedCall.providerJobId || '-'}</Descriptions.Item>
              <Descriptions.Item label="模型">{selectedCall.model || '-'}</Descriptions.Item>
              <Descriptions.Item label="Google搜索">{selectedCall.searchUsed == null ? '未记录' : selectedCall.searchUsed ? `已使用（${selectedCall.searchQueryCount || 0} 次）` : '未使用'}</Descriptions.Item>
              <Descriptions.Item label="输入 / 缓存 Token">{selectedCall.promptTokenCount ?? '-'} / {selectedCall.cachedContentTokenCount ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="输出 / 思考 Token">{selectedCall.candidatesTokenCount ?? '-'} / {selectedCall.thoughtsTokenCount ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="工具输入 Token">{selectedCall.toolUsePromptTokenCount ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="总 Token">{selectedCall.totalTokenCount ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="错误">{selectedCall.errorMessage ? '系统错误，请稍后重试' : '-'}</Descriptions.Item>
            </Descriptions>
            <ProviderResponsePanel call={selectedCall} />
            <div>
              <Text strong>模型输出正文（现有格式）</Text>
              <Typography.Paragraph copyable={Boolean(selectedCall.rawAiResponse)} style={{ whiteSpace: 'pre-wrap', maxHeight: 360, overflow: 'auto', marginTop: 8 }}>
                {selectedCall.rawAiResponse || '-'}
              </Typography.Paragraph>
            </div>
            <div>
              <Text strong>usageMetadata 原始数据</Text>
              <Typography.Paragraph copyable={Boolean(selectedCall.usageMetadata)} style={{ whiteSpace: 'pre-wrap', maxHeight: 260, overflow: 'auto', marginTop: 8 }}>
                {selectedCall.usageMetadata || '-'}
              </Typography.Paragraph>
            </div>
          </Space>
        )}
      </Drawer>
      <Drawer
        title={selectedItem ? `检测详情 · ${selectedItem.imageName}` : '检测详情'}
        open={Boolean(selectedItem)}
        width="min(720px, 94vw)"
        onClose={() => setSelectedItem(undefined)}
        destroyOnHidden
      >
        {selectedItem && (
          <Space direction="vertical" size={20} style={{ width: '100%' }}>
            <AuthorizedImage url={selectedItem.thumbnailUrl} alt={selectedItem.imageDescription || selectedItem.imageName} width={260} height={260} />
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="检测状态"><Tag color={itemStatus[selectedItem.status].color}>{itemStatus[selectedItem.status].text}</Tag></Descriptions.Item>
              <Descriptions.Item label="风险等级">{riskTag(selectedItem.riskLevel, selectedItem.status === 'SUCCESS' && selectedItem.promptVersion === 'relay_image_analysis_v1')}</Descriptions.Item>
              <Descriptions.Item label="风险原因/疑点">{selectedItem.riskReason || '-'}</Descriptions.Item>
              <Descriptions.Item label="TRO 案号">{labels(selectedItem.caseNumbers || [])}</Descriptions.Item>
              <Descriptions.Item label="判断置信度">{selectedItem.confidence == null ? '-' : `${selectedItem.confidence}%`}</Descriptions.Item>
              <Descriptions.Item label="可见风险元素">{labels(selectedItem.detectedElements)}</Descriptions.Item>
              <Descriptions.Item label="英文描述关键词">{selectedItem.imageDescription || '-'}</Descriptions.Item>
              <Descriptions.Item label="画面主体">{labels(selectedItem.subjects)}</Descriptions.Item>
              <Descriptions.Item label="场景">{labels(selectedItem.scenes)}</Descriptions.Item>
              <Descriptions.Item label="主题">{labels(selectedItem.themes)}</Descriptions.Item>
              <Descriptions.Item label="视觉风格">{labels(selectedItem.visualStyles)}</Descriptions.Item>
              <Descriptions.Item label="主色">{labels(selectedItem.colors)}</Descriptions.Item>
              <Descriptions.Item label="模型">{selectedItem.model || '-'}</Descriptions.Item>
              <Descriptions.Item label={selectedItem.executionMode === 'RELAY' ? '实际使用提示词' : '提示词版本'}>
                {selectedItem.promptVersion === 'relay_image_analysis_v1' ? 'image_analysis_prompt.txt' : selectedItem.promptVersion || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="执行方式">{selectedItem.executionMode ? executionModeLabel[selectedItem.executionMode] : '-'}</Descriptions.Item>
              <Descriptions.Item label="Google 搜索">{selectedItem.searchUsed == null ? '未记录' : selectedItem.searchUsed ? `已使用（${selectedItem.searchQueryCount || 0} 次）` : '未使用'}</Descriptions.Item>
              <Descriptions.Item label="完成时间">{selectedItem.finishedAt || '-'}</Descriptions.Item>
              {selectedItem.errorMessage && <Descriptions.Item label="异常信息"><Text type="danger">系统错误，请稍后重试</Text></Descriptions.Item>}
            </Descriptions>
          </Space>
        )}
      </Drawer>
    </PageContainer>
  );
}
