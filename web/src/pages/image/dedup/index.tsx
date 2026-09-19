import {
  CheckCircleOutlined,
  CheckOutlined,
  CloseOutlined,
  DeleteOutlined,
  DownloadOutlined,
  InboxOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  SearchOutlined
} from '@ant-design/icons';
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { history, useLocation } from '@umijs/max';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Collapse,
  Descriptions,
  DatePicker,
  Drawer,
  Empty,
  Flex,
  Image,
  Input,
  InputNumber,
  List,
  message,
  Modal,
  Pagination,
  Popconfirm,
  Progress,
  Radio,
  Select,
  Space,
  Table,
  Tag,
  Tabs,
  Typography,
  Upload,
  type TableColumnsType,
  type UploadFile
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  getImageAccess,
  getImageStorageMode,
  prepareImageUploads,
  completeImageUploads,
  postImageToOss,
  confirmImageBatch,
  createImageBatch,
  deleteApprovedImage,
  getApprovedImage,
  getImageBatch,
  getImageContent,
  getImageCategoryOptions,
  getImageHealth,
  listImageAspectRatios,
  listImageBatches,
  listApprovedImages,
  repairImageVectorIndex,
  resetImageData,
  prepareImageReplacement,
  replaceApprovedImage,
  listImageReplacementLogs,
  rescanImageInfringement,
  retryImageBatch,
  startImageBatch,
  uploadBatchFiles
} from '@/api/image';
import type { BatchStatus, BatchSummary, BatchView, HealthView, ImageCategory, ImageId, ImageView, ImageQuery, ImageReplacementLog, InfringementMode, SimilarImageView } from '@/api/image/types';
import { useUserStore } from '@/stores/userStore';
import { hasPermi, hasRole } from '@/utils/permission';
import { useTableExport } from '@/hooks/useTableExport';

const currentBatchKey = 'image-dedup-current-batch';
const maxFilesPerBatch = 1000;
const uploadPreviewPageSize = 100;
const reviewCandidatePageSize = 6;
const aspectRatioOrder = ['1:2', '9:16', '2:3', '3:4', '4:5', '1:1', '5:4', '4:3', '3:2', '16:9', '2:1', '其他'];
const infringementModeText: Record<InfringementMode, string> = {
  RELAY: '中转站',
  OFFICIAL_HYBRID: '官方混合',
  OFFICIAL_STANDARD: '官方 Standard',
  OFFICIAL_BATCH: '官方 Batch',
  OFFICIAL_FLEX: '官方 Flex'
};

const isSupportedImageFile = (fileName: string) => /\.(?:jpe?g|png|webp)$/i.test(fileName);

type ImagePageKey = 'upload' | 'tasks' | 'review' | 'library';
type WorkflowPageKey = Exclude<ImagePageKey, 'library'>;
type UploadIssue = { name: string; reason: string };

const pageByPath: Partial<Record<string, { key: Exclude<ImagePageKey, 'upload'>; title: string }>> = {
  '/image/tasks': { key: 'tasks', title: '上传进度' },
  '/image/review': { key: 'review', title: '人工审核' },
  '/image/library': { key: 'library', title: '正式图库' }
};

const pageDescriptions: Record<ImagePageKey, string> = {
  upload: '选择图片类目并上传文件，系统将自动完成相似度检测。',
  tasks: '查看图片上传和检测进度，离开页面不会中断后台任务。',
  review: '仅审核达到相似阈值的图片；低于阈值的图片已自动进入图库。',
  library: '查看、筛选和维护已经进入正式图库的图片资产。'
};

const workflowTabs: Array<{ key: WorkflowPageKey; label: string; path: string }> = [
  { key: 'upload', label: '上传图片', path: '/image/upload' },
  { key: 'tasks', label: '上传进度', path: '/image/upload?tab=tasks' },
  { key: 'review', label: '人工审核', path: '/image/upload?tab=review' }
];

function UploadPreviewCard({
  file,
  selected,
  onToggle,
  onRemove
}: {
  file: UploadFile;
  selected: boolean;
  onToggle: () => void;
  onRemove: () => void;
}) {
  const [source, setSource] = useState<string>();

  useEffect(() => {
    const rawFile = file.originFileObj;
    if (!rawFile) return;
    const objectUrl = URL.createObjectURL(rawFile);
    setSource(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [file.originFileObj]);

  return (
    <div className={`image-upload-card${selected ? ' is-selected' : ''}`}>
      <button type="button" className="image-upload-card__select" aria-pressed={selected} onClick={onToggle}>
        {source ? <img src={source} alt={file.name} /> : <span className="image-upload-card__placeholder">图片</span>}
        <span className="image-upload-card__checkbox" aria-hidden="true">{selected && <CheckOutlined />}</span>
        <span className="image-upload-card__name" title={file.name}>{file.name}</span>
      </button>
      <Button
        type="text"
        shape="circle"
        size="small"
        danger
        icon={<CloseOutlined />}
        className="image-upload-card__remove"
        aria-label={`移除${file.name}`}
        onClick={event => {
          event.stopPropagation();
          onRemove();
        }}
      />
    </div>
  );
}

function AuthorizedImage({ url, width = 96, height = 96, alt = '' }: { url?: string; width?: number; height?: number; alt?: string }) {
  const [source, setSource] = useState<string>();
  const [failed, setFailed] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    let objectUrl: string | undefined;
    if (!url) {
      setSource(undefined);
      return;
    }
    setSource(undefined);
    setFailed(false);
    void getImageAccess(url).then(async response => {
      if (!active) return;
      if (response.data.startsWith('https://')) {
        setSource(response.data);
      } else {
        const blob = await getImageContent(response.data);
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setSource(objectUrl);
      }
    }).catch(() => { if (active) setFailed(true); });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [url, reload]);

  if (!url) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无匹配图" />;
  if (failed) return <Button size="small" onClick={() => setReload(value => value + 1)}>预览失败，重试</Button>;
  return source ? <Image src={source} alt={alt} loading="lazy" onError={() => { if (reload < 1) setReload(reload + 1); else setFailed(true); }} width={width} height={height} style={{ objectFit: 'contain' }} /> : <Progress type="circle" size={32} percent={60} showInfo={false} />;
}

function SimilarImageGrid({ images }: { images: SimilarImageView[] }) {
  const [page, setPage] = useState(1);

  useEffect(() => setPage(1), [images]);

  if (!images.length) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有可展示的相似图片" />;
  const firstIndex = (page - 1) * reviewCandidatePageSize;
  const visibleImages = images.slice(firstIndex, firstIndex + reviewCandidatePageSize);
  return (
    <>
      <div className="image-similar-grid">
        {visibleImages.map((candidate, index) => {
          const rank = firstIndex + index + 1;
          return (
            <article className="image-similar-card" key={String(candidate.imageId)}>
              <div className="image-similar-card__preview">
                <AuthorizedImage
                  url={candidate.thumbnailUrl}
                  width={240}
                  height={180}
                  alt={`第${rank}名相似图片：${candidate.name}`}
                />
              </div>
              <Space size={4} wrap>
                <Tag color="purple">#{rank}</Tag>
                <Tag color={candidate.source === 'BATCH' ? 'blue' : 'green'}>
                  {candidate.source === 'BATCH' ? '本批次' : '正式图库'}
                </Tag>
                <Typography.Text strong>{(Number(candidate.similarity) * 100).toFixed(2)}%</Typography.Text>
              </Space>
              <Typography.Text ellipsis={{ tooltip: candidate.name }}>{candidate.name}</Typography.Text>
              <Typography.Text type="secondary">ID：{String(candidate.imageId)}</Typography.Text>
            </article>
          );
        })}
      </div>
      {images.length > reviewCandidatePageSize && (
        <Flex className="image-similar-pagination" justify="space-between" align="center" gap={12} wrap>
          <Typography.Text type="secondary">
            第 {firstIndex + 1}-{Math.min(firstIndex + reviewCandidatePageSize, images.length)} 张，共 {images.length} 张
          </Typography.Text>
          <Pagination
            size="small"
            current={page}
            pageSize={reviewCandidatePageSize}
            total={images.length}
            showSizeChanger={false}
            onChange={setPage}
          />
        </Flex>
      )}
    </>
  );
}

function statusTag(status: ImageView['status']) {
  const colors: Record<ImageView['status'], string> = {
    UPLOADING: 'default', APPROVING: 'processing', APPROVAL_FAILED: 'error', DELETE_PENDING: 'default',
    UPLOADED: 'default',
    CHECKING: 'processing',
    WAIT_REVIEW: 'warning',
    APPROVED: 'success',
    REJECTED: 'default',
    REPLACED: 'default',
    FAILED: 'error'
  };
  const labels: Record<ImageView['status'], string> = {
    UPLOADING: '上传中', APPROVING: '入库中', APPROVAL_FAILED: '入库失败', DELETE_PENDING: '已拒绝，待清理',
    UPLOADED: '已上传',
    CHECKING: '检测中',
    WAIT_REVIEW: '待确认',
    APPROVED: '已入库',
    REJECTED: '已废弃',
    REPLACED: '已替换',
    FAILED: '失败'
  };
  return <Tag color={colors[status]}>{labels[status]}</Tag>;
}

function infringementTag(image: ImageView) {
  if (image.infringementStatus === 'FAILED') return <Tag color="error">检测失败</Tag>;
  if (image.infringementStatus === 'SUCCESS' && image.infringementRiskLevel === 'HIGH') {
    return <Tag color="red">高风险</Tag>;
  }
  if (image.infringementStatus === 'SUCCESS' && image.infringementRiskLevel === 'LOW') {
    return <Tag color="green">低风险</Tag>;
  }
  if (image.infringementStatus === 'SUCCESS' && image.infringementRiskLevel === 'NEEDS_REVIEW') {
    return <Tag color="warning">疑似侵权</Tag>;
  }
  if (image.infringementStatus === 'PENDING' || image.infringementStatus === 'PROCESSING') {
    return <Tag color="processing">检测中</Tag>;
  }
  return <Tag>未检测</Tag>;
}

function batchStatusTag(status: BatchStatus) {
  const values: Record<BatchStatus, { color: string; label: string }> = {
    UPLOADING: { color: 'default', label: '上传中' },
    QUEUED: { color: 'blue', label: '排队中' },
    CHECKING: { color: 'processing', label: '检测中' },
    WAIT_REVIEW: { color: 'warning', label: '待确认' },
    CONFIRMING: { color: 'processing', label: '入库中' },
    COMPLETED: { color: 'success', label: '已完成' },
    FAILED: { color: 'error', label: '失败' }
  };
  return <Tag color={values[status].color}>{values[status].label}</Tag>;
}

function isAdmissionProhibited(image: ImageView) {
  return image.matchMethod === 'EXACT_HASH'
    || image.similarity != null && Number(image.similarity) > 0.95;
}

export default function ImageDedupPage() {
  const location = useLocation();
  const userInfo = useUserStore(state => state.userInfo);
  const canUpload = hasPermi(userInfo, ['image:dedup:upload']) && hasPermi(userInfo, ['image:dedup:start']);
  const canQuery = hasPermi(userInfo, ['image:dedup:query']);
  const canConfirm = hasPermi(userInfo, ['image:dedup:confirm']);
  const canRetry = hasPermi(userInfo, ['image:dedup:retry']);
  const canRemove = hasRole(userInfo, ['superadmin']);
  const { exportFile } = useTableExport();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [selectedFileUids, setSelectedFileUids] = useState<string[]>([]);
  const [uploadIssues, setUploadIssues] = useState<UploadIssue[]>([]);
  const [categories, setCategories] = useState<ImageCategory[]>([]);
  const [categoryId, setCategoryId] = useState<ImageId>();
  useEffect(() => {
    const preventFileNavigation = (event: DragEvent) => {
      if (!event.dataTransfer?.types.includes('Files')) return;
      event.preventDefault();
      const target = event.target as Element | null;
      if (event.type === 'drop' && (categoryId == null || !target?.closest('.image-upload-dropzone'))) {
        message.warning(categoryId == null ? '请先选择图片类目，再拖入图片' : '请把图片拖到上传区域');
      }
    };
    window.addEventListener('dragover', preventFileNavigation);
    window.addEventListener('drop', preventFileNavigation);
    return () => {
      window.removeEventListener('dragover', preventFileNavigation);
      window.removeEventListener('drop', preventFileNavigation);
    };
  }, [categoryId]);
  const [infringementMode, setInfringementMode] = useState<InfringementMode>('RELAY');
  const [threshold, setThreshold] = useState(0.6);
  const [uploading, setUploading] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [uploadPreviewPage, setUploadPreviewPage] = useState(1);
  const [confirming, setConfirming] = useState(false);
  const [batch, setBatch] = useState<BatchView>();
  const [health, setHealth] = useState<HealthView>();
  const [library, setLibrary] = useState<ImageView[]>([]);
  const [libraryTotal, setLibraryTotal] = useState(0);
  const [libraryPage, setLibraryPage] = useState(1);
  const [libraryPageSize, setLibraryPageSize] = useState(12);
  const [keyword, setKeyword] = useState('');
  const [libraryBatchId, setLibraryBatchId] = useState('');
  const [libraryCategoryId, setLibraryCategoryId] = useState<ImageId>();
  const [libraryAspectRatio, setLibraryAspectRatio] = useState<string>();
  const [libraryFilters, setLibraryFilters] = useState<ImageQuery>(() => ({ imageId: new URLSearchParams(location.search).get('imageId') || undefined }));
  const libraryFiltersRef = useRef(libraryFilters);
  const updateLibraryFilters = (patch: Partial<ImageQuery>) => {
    libraryFiltersRef.current = { ...libraryFiltersRef.current, ...patch };
    setLibraryFilters(libraryFiltersRef.current);
  };
  const [replacementLogs, setReplacementLogs] = useState<ImageReplacementLog[]>([]);
  const [replacementLogsOpen, setReplacementLogsOpen] = useState(false);
  const [replacingImageId, setReplacingImageId] = useState<ImageId>();
  const [aspectRatios, setAspectRatios] = useState<string[]>([]);
  const [libraryLoading, setLibraryLoading] = useState(false);
  const [detailImage, setDetailImage] = useState<ImageView>();
  const [rescanningImage, setRescanningImage] = useState(false);
  const [clearLibraryOpen, setClearLibraryOpen] = useState(false);
  const [clearingLibrary, setClearingLibrary] = useState(false);
  const [tasks, setTasks] = useState<BatchSummary[]>([]);
  const [taskTotal, setTaskTotal] = useState(0);
  const [taskPage, setTaskPage] = useState(1);
  const [taskPageSize, setTaskPageSize] = useState(10);
  const [taskLoading, setTaskLoading] = useState(false);
  const [taskDrawerOpen, setTaskDrawerOpen] = useState(false);
  const [reviewBatches, setReviewBatches] = useState<BatchSummary[]>([]);
  const [reviewDetails, setReviewDetails] = useState<Record<string, BatchView>>({});
  const [reviewSelectedIds, setReviewSelectedIds] = useState<Record<string, ImageId[]>>({});
  const [activeReviewKeys, setActiveReviewKeys] = useState<string[]>([]);
  const reviewInitialOpenRef = useRef(false);
  const submittingRef = useRef(false);
  const selectedFileUidSet = useMemo(() => new Set(selectedFileUids), [selectedFileUids]);
  const failedImages = useMemo(
    () => batch?.images.filter(image => image.status === 'FAILED' || image.status === 'APPROVAL_FAILED') || [],
    [batch]
  );
  const appendUploadIssues = (issues: UploadIssue[]) => {
    setUploadIssues(current => [...current, ...issues]);
  };
  const visibleUploadFiles = useMemo(() => files.slice(
    (uploadPreviewPage - 1) * uploadPreviewPageSize,
    uploadPreviewPage * uploadPreviewPageSize
  ), [files, uploadPreviewPage]);
  const currentPage = pageByPath[location.pathname];
  const requestedTab = new URLSearchParams(location.search).get('tab');
  const requestedPageKey: ImagePageKey = location.pathname === '/image/upload' && (requestedTab === 'tasks' || requestedTab === 'review')
    ? requestedTab
    : currentPage?.key ?? 'upload';
  const availableWorkflowTabs = workflowTabs.filter(tab => tab.key === 'upload' ? canUpload : canQuery);
  const pageKey: ImagePageKey = requestedPageKey === 'library' || availableWorkflowTabs.some(tab => tab.key === requestedPageKey)
    ? requestedPageKey
    : availableWorkflowTabs[0]?.key ?? requestedPageKey;
  const pageTitle = pageKey === 'library' ? '正式图库' : '上传与人工复核';

  const loadHealth = useCallback(async () => {
    const response = await getImageHealth();
    setHealth(response.data);
  }, []);

  const loadCategoryOptions = useCallback(async () => {
    const response = await getImageCategoryOptions();
    const next = Array.from(response.data || []);
    setCategories(next);
  }, []);

  const loadAspectRatios = useCallback(async () => {
    const response = await listImageAspectRatios();
    setAspectRatios(Array.from(response.data || []));
  }, []);

  const loadLibrary = useCallback(async (
    page: number,
    pageSize: number,
    search: string,
    selectedCategoryId?: ImageId,
    batchId?: string,
    aspectRatio?: string
  ) => {
    setLibraryLoading(true);
    try {
      const response = await listApprovedImages({
        ...libraryFiltersRef.current,
        pageNum: page,
        pageSize,
        keyword: search || undefined,
        categoryId: selectedCategoryId,
        batchId: batchId || undefined,
        aspectRatio
      });
      setLibrary(Array.from(response.data.rows || []));
      setLibraryTotal(response.data.total);
      setLibraryPage(page);
      setLibraryPageSize(pageSize);
    } finally {
      setLibraryLoading(false);
    }
  }, []);

  const aspectRatioOptions = useMemo(() => Array.from(aspectRatios)
    .sort((left, right) => {
      const leftIndex = aspectRatioOrder.indexOf(left);
      const rightIndex = aspectRatioOrder.indexOf(right);
      return (leftIndex < 0 ? aspectRatioOrder.length : leftIndex)
        - (rightIndex < 0 ? aspectRatioOrder.length : rightIndex)
        || left.localeCompare(right);
    })
    .map(value => ({ value, label: value })), [aspectRatios]);

  const repairVectorIndex = useCallback(async () => {
    const response = await repairImageVectorIndex();
    message.success(`索引修复完成：补建 ${response.data.restoredPoints} 条，移除 ${response.data.removedPoints} 条`);
    await Promise.all([
      loadHealth(),
      loadLibrary(libraryPage, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio)
    ]);
  }, [keyword, libraryAspectRatio, libraryBatchId, libraryCategoryId, libraryPage, libraryPageSize, loadHealth, loadLibrary]);

  const clearLibrary = useCallback(async () => {
    setClearingLibrary(true);
    try {
      const response = await resetImageData();
      message.success(`图库已清空：${response.data.assets} 张图片、${response.data.batches} 个批次`);
      setClearLibraryOpen(false);
      setDetailImage(undefined);
      localStorage.removeItem(currentBatchKey);
      await Promise.all([
        loadHealth(),
        loadAspectRatios(),
        loadLibrary(1, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio)
      ]);
    } finally {
      setClearingLibrary(false);
    }
  }, [keyword, libraryAspectRatio, libraryBatchId, libraryCategoryId, libraryPageSize, loadAspectRatios, loadHealth, loadLibrary]);

  const loadBatch = useCallback(async (batchId: ImageId) => {
    const response = await getImageBatch(batchId);
    const next = response.data;
    setBatch(next);
    return next;
  }, []);

  const loadReviewBatch = useCallback(async (batchId: ImageId) => {
    const response = await getImageBatch(batchId);
    const next = response.data;
    const key = String(next.id);
    setReviewDetails(current => ({ ...current, [key]: next }));
    setReviewSelectedIds(current => current[key] ? current : {
      ...current,
      [key]: next.images
        .filter(image => image.status === 'WAIT_REVIEW' && !image.duplicate && !isAdmissionProhibited(image))
        .map(image => image.id)
    });
  }, []);

  const loadTasks = useCallback(async (page: number, pageSize: number) => {
    setTaskLoading(true);
    try {
      const response = await listImageBatches({ pageNum: page, pageSize });
      setTasks(Array.from(response.data.rows || []));
      setTaskTotal(response.data.total);
      setTaskPage(page);
      setTaskPageSize(pageSize);
    } finally {
      setTaskLoading(false);
    }
  }, []);

  const loadReviewBatches = useCallback(async (completedBatchId?: ImageId) => {
    const response = await listImageBatches({ pageNum: 1, pageSize: 100, status: 'WAIT_REVIEW' });
    const rows = Array.from(response.data.rows || []).sort((left, right) =>
      (left.createTime || '').localeCompare(right.createTime || '')
    );
    const available = new Set(rows.map(row => String(row.id)));
    const completedKey = completedBatchId == null ? undefined : String(completedBatchId);
    setReviewBatches(rows);
    setActiveReviewKeys(current => {
      const remaining = current.filter(key => available.has(key) && key !== completedKey);
      if (remaining.length || reviewInitialOpenRef.current) return remaining;
      reviewInitialOpenRef.current = true;
      const requestedBatchId = new URLSearchParams(location.search).get('batchId');
      return (rows.find(row => String(row.id) === requestedBatchId) ? [requestedBatchId!] : rows.slice(0, 1).map(row => String(row.id)));
    });
  }, []);

  useEffect(() => {
    void loadHealth();
    if ((pageKey === 'upload' && canUpload) || pageKey === 'library') void loadCategoryOptions();
    if (pageKey === 'upload' && canUpload && !canRemove) void getImageStorageMode().then(result => setThreshold(Number(result.data.similarityThreshold)));
    if (pageKey === 'library') {
      void loadLibrary(1, libraryPageSize, '', undefined, undefined, undefined);
      void loadAspectRatios();
    }
    if (pageKey === 'tasks') void loadTasks(1, taskPageSize);
    if (pageKey === 'upload' && canQuery) {
      const savedBatchId = localStorage.getItem(currentBatchKey);
      if (savedBatchId) {
        void loadBatch(savedBatchId).catch(() => localStorage.removeItem(currentBatchKey));
      }
    }
  }, [canUpload, canRemove, libraryPageSize, loadAspectRatios, loadBatch, loadCategoryOptions, loadHealth, loadLibrary, loadTasks, pageKey, taskPageSize]);

  useEffect(() => {
    const lastPage = Math.max(1, Math.ceil(files.length / uploadPreviewPageSize));
    if (uploadPreviewPage > lastPage) setUploadPreviewPage(lastPage);
  }, [files.length, uploadPreviewPage]);

  useEffect(() => {
    if (pageKey !== 'tasks' || !taskDrawerOpen || !batch || !['QUEUED', 'CHECKING', 'CONFIRMING'].includes(batch.status)) return;
    const timer = window.setInterval(() => {
      void loadBatch(batch.id).then(next => {
        if (!['QUEUED', 'CHECKING', 'CONFIRMING'].includes(next.status)) {
          void Promise.all([loadHealth(), loadTasks(1, taskPageSize)]);
        }
      });
    }, 2000);
    return () => window.clearInterval(timer);
  }, [batch, loadBatch, loadHealth, loadTasks, pageKey, taskDrawerOpen, taskPageSize]);

  useEffect(() => {
    if (pageKey !== 'tasks' || !tasks.some(task => ['QUEUED', 'CHECKING', 'CONFIRMING'].includes(task.status))) return;
    const timer = window.setInterval(() => void loadTasks(taskPage, taskPageSize), 2000);
    return () => window.clearInterval(timer);
  }, [loadTasks, pageKey, taskPage, taskPageSize, tasks]);

  useEffect(() => {
    if (pageKey !== 'review') return;
    void loadReviewBatches();
    const timer = window.setInterval(() => void loadReviewBatches(), 3000);
    return () => window.clearInterval(timer);
  }, [loadReviewBatches, pageKey]);

  useEffect(() => {
    activeReviewKeys.forEach(key => {
      if (!reviewDetails[key]) void loadReviewBatch(key);
    });
  }, [activeReviewKeys, loadReviewBatch, reviewDetails]);

  const processedPercent = batch?.totalCount
    ? Math.round((batch.processedCount / batch.totalCount) * 100)
    : uploadProgress;

  const taskColumns: TableColumnsType<BatchSummary> = [
    { title: '批次 ID', dataIndex: 'id', width: 210 },
    { title: '创建人', dataIndex: 'creatorName', width: 120 },
    { title: '类目', dataIndex: 'categoryName', width: 120 },
    { title: '侵权检测', dataIndex: 'infringementMode', width: 110, render: value => infringementModeText[value as InfringementMode] },
    { title: '状态', dataIndex: 'status', width: 100, render: value => batchStatusTag(value) },
    {
      title: '进度',
      width: 180,
      render: (_, row) => (
        <Progress
          size="small"
          percent={row.totalCount ? Math.round((row.processedCount / row.totalCount) * 100) : 0}
          status={row.status === 'FAILED' ? 'exception' : row.status === 'COMPLETED' ? 'success' : 'active'}
        />
      )
    },
    { title: '总数', dataIndex: 'totalCount', width: 70 },
    { title: '重复', dataIndex: 'duplicateCount', width: 70 },
    { title: '失败', dataIndex: 'failedCount', width: 70 },
    { title: '检测完成时间', dataIndex: 'detectionCompletedAt', width: 180, render: value => value || '-' },
    { title: '创建时间', dataIndex: 'createTime', width: 180 },
    {
      title: '操作',
      width: 90,
      render: (_, row) => (
        <Button
          type="link"
          onClick={async () => {
            localStorage.setItem(currentBatchKey, String(row.id));
            await loadBatch(row.id);
            setTaskDrawerOpen(true);
          }}
        >
          查看
        </Button>
      )
    }
  ];

  const start = async () => {
    if (submittingRef.current) return;
    if (categoryId == null) {
      message.warning('请选择图片类目');
      return;
    }
    if (!selectedFileUids.length) {
      message.warning('请先选择需要上传的图片');
      return;
    }
    if (selectedFileUids.length > maxFilesPerBatch) {
      message.warning(`一个批次最多上传${maxFilesPerBatch}张图片`);
      return;
    }
    submittingRef.current = true;
    setUploading(true);
    setUploadProgress(0);
    try {
      const created = await createImageBatch({
        similarityThreshold: threshold,
        categoryId,
        infringementMode
      });
      const batchId = created.data.id;
      setBatch(created.data);
      if (canQuery) localStorage.setItem(currentBatchKey, String(batchId));
      const rawFiles = files
        .filter(file => selectedFileUidSet.has(file.uid))
        .map(file => file.originFileObj || (file as unknown as File));
      const selectedUploadFiles = files.filter(file => selectedFileUidSet.has(file.uid));
      let retryUids: string[] = [];
      const storageMode = (await getImageStorageMode()).data;
      if (storageMode.directUpload) {
        const session = (await prepareImageUploads(batchId, rawFiles)).data;
        const loaded = Array.from<number>({ length: rawFiles.length }).fill(0);
        const totalBytes = rawFiles.reduce((sum, file) => sum + file.size, 0);
        let cursor = 0;
        let lastProgressAt = 0;
        const failedIndexes = new Map<number, string>();
        const uploadOne = async () => {
          while (cursor < rawFiles.length) {
            const index = cursor++;
            let lastError: unknown;
            for (let attempt = 0; attempt < 3; attempt += 1) {
              try {
                await postImageToOss(session, session.files[index], rawFiles[index], bytes => {
                  loaded[index] = bytes;
                  const uploaded = loaded.reduce((sum, value) => sum + value, 0);
                  const now = performance.now();
                  if (now - lastProgressAt >= 100 || uploaded === totalBytes) {
                    lastProgressAt = now;
                    setUploadProgress(Math.round((uploaded / totalBytes) * 100));
                  }
                });
                loaded[index] = rawFiles[index].size;
                lastError = undefined;
                break;
              } catch (error) {
                loaded[index] = 0;
                lastError = error;
              }
            }
            if (lastError) {
              failedIndexes.set(index, lastError instanceof Error ? lastError.message : '上传失败，请检查网络后重试');
            }
          }
        };
        const concurrency = Math.min(session.uploadConcurrency || storageMode.uploadConcurrency, rawFiles.length);
        await Promise.all(Array.from({ length: concurrency }, uploadOne));
        const failed = new Set(failedIndexes.keys());
        const successfulIds = session.files.filter((_, index) => !failed.has(index)).map(file => file.imageId);
        const failedIds = session.files.filter((_, index) => failed.has(index)).map(file => file.imageId);
        await completeImageUploads(batchId, successfulIds, failedIds);
        retryUids = Array.from(failedIndexes.keys()).map(index => selectedUploadFiles[index].uid);
        if (failedIndexes.size) {
          appendUploadIssues(Array.from(failedIndexes, ([index, reason]) => ({
            name: selectedUploadFiles[index].name,
            reason
          })));
        }
        if (!successfulIds.length) throw new Error('全部图片上传失败，请检查网络后重试');
        setUploadProgress(100);
      } else {
        let cursor = 0;
        let completed = 0;
        const failedIndexes = new Map<number, string>();
        const uploadOne = async () => {
          while (cursor < rawFiles.length) {
            const index = cursor++;
            try {
              await uploadBatchFiles(batchId, [rawFiles[index]]);
            } catch (error) {
              failedIndexes.set(index, error instanceof Error ? error.message : '上传失败，请重试');
            } finally {
              completed += 1;
              setUploadProgress(Math.round((completed / rawFiles.length) * 100));
            }
          }
        };
        const concurrency = Math.min(Math.max(1, storageMode.uploadConcurrency), rawFiles.length);
        await Promise.all(Array.from({ length: concurrency }, uploadOne));
        retryUids = Array.from(failedIndexes.keys()).map(index => selectedUploadFiles[index].uid);
        if (failedIndexes.size) {
          appendUploadIssues(Array.from(failedIndexes, ([index, reason]) => ({
            name: selectedUploadFiles[index].name,
            reason
          })));
        }
        if (failedIndexes.size === rawFiles.length) throw new Error('全部图片上传失败，请检查后重试');
      }
      await startImageBatch(batchId);
      if (canQuery) {
        await loadBatch(batchId);
        await loadTasks(1, taskPageSize);
      }
      setFiles(current => current.filter(file => !selectedFileUidSet.has(file.uid) || retryUids.includes(file.uid)));
      setSelectedFileUids(retryUids);
      setUploadPreviewPage(1);
      if (canQuery) history.push('/image/upload?tab=tasks');
      if (retryUids.length) {
        message.warning(`${retryUids.length}张图片上传失败并已保留，其余图片已进入检测`);
      } else {
        message.success(canQuery
          ? '上传完成，已进入后台检测；可以离开当前页面，稍后从“上传进度”查看结果'
          : '上传完成，已进入后台检测');
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '图片上传失败，请稍后重试');
    } finally {
      submittingRef.current = false;
      setUploading(false);
    }
  };

  const confirmReviewBatch = async (batchId: ImageId) => {
    const selectedIds = reviewSelectedIds[String(batchId)] || [];
    setConfirming(true);
    try {
      await confirmImageBatch(batchId, selectedIds);
      message.success(`已提交审核决定，${selectedIds.length}张图片正在后台入库，可在任务中查看结果`);
      await Promise.all([
        loadLibrary(1, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio),
        loadHealth(),
        loadTasks(1, taskPageSize),
        loadReviewBatches(batchId)
      ]);
    } finally {
      setConfirming(false);
    }
  };

  const retry = async () => {
    if (!batch) return;
    await retryImageBatch(batch.id);
    await loadBatch(batch.id);
    await loadTasks(1, taskPageSize);
    message.success('失败图片已重新排队');
  };

  const rescanImage = async () => {
    if (!detailImage) return;
    setRescanningImage(true);
    try {
      const response = await rescanImageInfringement(detailImage.id);
      if (response.data.imageCount === 0) {
        message.info('已有成功检测结果，无需重复调用 AI');
      } else {
        message.success(`已创建重新检测任务：${String(response.data.taskId)}`);
      }
      const latest = await getApprovedImage(detailImage.id);
      setDetailImage(latest.data);
      await loadLibrary(libraryPage, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio);
    } finally {
      setRescanningImage(false);
    }
  };

  const uploadPanel = (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <ProCard
        title="新建上传批次"
        extra={<Tag color="blue">需先选择图片类目</Tag>}
        className="image-workflow-card"
      >
        <div className="image-upload-config">
          <label>
            <Typography.Text strong>图片类目</Typography.Text>
            <Select
              value={categoryId}
              options={categories.map(category => ({ value: category.id, label: category.name }))}
              placeholder="请选择类目"
              onChange={setCategoryId}
            />
          </label>
          <label>
            <Typography.Text strong>重复阈值</Typography.Text>
            <InputNumber min={0.5} max={0.99} step={0.01} value={threshold} disabled={!canRemove} onChange={value => setThreshold(value ?? 0.6)} />
          </label>
          <label className="image-upload-config__mode">
            <Typography.Text strong>侵权检测模式（必选）</Typography.Text>
            <Radio.Group value={infringementMode} onChange={event => setInfringementMode(event.target.value)}>
              <Space direction="vertical" size={4}>
                <Radio value="RELAY">中转站（默认）</Radio>
                <Typography.Text type="secondary">OpenLux，支持 Google Search</Typography.Text>
                <Radio value="OFFICIAL_HYBRID">官方混合（≤100 张 Standard，&gt;100 张 Batch）</Radio>
                <Typography.Text type="secondary">小批次实时返回；大批次约半价异步处理，最长可能等待24小时</Typography.Text>
              </Space>
            </Radio.Group>
          </label>
        </div>
        <Upload.Dragger
            className="image-upload-dropzone"
            multiple
            disabled={categoryId == null}
            fileList={files}
            maxCount={maxFilesPerBatch}
            showUploadList={false}
            beforeUpload={(file, incomingFiles) => {
              const unsupportedFiles = incomingFiles.filter(item => !isSupportedImageFile(item.name));
              if (unsupportedFiles.length) {
                if (file.uid === incomingFiles[0]?.uid) {
                  const examples = unsupportedFiles.slice(0, 3).map(item => item.name).join('、');
                  const remaining = unsupportedFiles.length > 3 ? `等 ${unsupportedFiles.length} 张` : '';
                  appendUploadIssues(unsupportedFiles.map(item => ({
                    name: item.name,
                    reason: '文件格式不受支持；仅支持 JPEG、PNG、WebP'
                  })));
                  message.error(`本次选择包含不支持的格式：${examples}${remaining}。仅支持 JPEG、PNG、WebP，请检查后重新选择。`, 6);
                }
                return Upload.LIST_IGNORE;
              }
              if (files.length + incomingFiles.length > maxFilesPerBatch) {
                if (file.uid === incomingFiles[0]?.uid) {
                  appendUploadIssues(incomingFiles.map(item => ({
                    name: item.name,
                    reason: `单个批次最多 ${maxFilesPerBatch} 张图片`
                  })));
                  message.error(`每批最多 ${maxFilesPerBatch} 张，当前已有 ${files.length} 张，本次选择 ${incomingFiles.length} 张。本次文件未加入，请减少数量后重试。`, 6);
                }
                return Upload.LIST_IGNORE;
              }
              return false;
            }}
            onChange={({ fileList }) => {
              const previousUids = new Set(files.map(file => file.uid));
              const nextUids = new Set(fileList.map(file => file.uid));
              setFiles(fileList);
              setSelectedFileUids(current => {
                const nextSelected = new Set(current.filter(uid => nextUids.has(uid)));
                fileList.filter(file => !previousUids.has(file.uid)).forEach(file => nextSelected.add(file.uid));
                return Array.from(nextSelected);
              });
            }}
          >
          <p className="ant-upload-drag-icon"><InboxOutlined /></p>
          <p className="ant-upload-text">{categoryId == null ? '请先选择图片类目' : '把图片拖到这里，或点击选择'}</p>
          <p className="ant-upload-hint">JPEG、PNG、WebP · 每批最多1000张</p>
        </Upload.Dragger>
        {uploadIssues.length > 0 && (
          <Alert
            type="error"
            showIcon
            closable
            onClose={() => setUploadIssues([])}
            message={`${uploadIssues.length} 个文件未加入上传队列或上传失败`}
            description={(
              <List
                size="small"
                pagination={{ pageSize: 10, showSizeChanger: false, hideOnSinglePage: true }}
                dataSource={uploadIssues}
                renderItem={issue => <List.Item>{issue.name}：{issue.reason}</List.Item>}
              />
            )}
          />
        )}
        {uploading && (
          <div className="image-upload-progress">
            <Flex justify="space-between">
              <Typography.Text>正在上传图片</Typography.Text>
              <Typography.Text>{uploadProgress}%</Typography.Text>
            </Flex>
            <Progress percent={uploadProgress} showInfo={false} />
          </div>
        )}
        {files.length > 0 && (
          <>
            <div className="image-upload-toolbar">
              <Typography.Text strong>已加入 {files.length} 张，已选 {selectedFileUids.length} 张</Typography.Text>
              <Space>
                <Button type="link" onClick={() => setSelectedFileUids(files.map(file => file.uid))}>全选</Button>
                <Button type="link" onClick={() => setSelectedFileUids([])}>取消全选</Button>
                <Button type="link" danger onClick={() => { setFiles([]); setSelectedFileUids([]); }}>清空</Button>
              </Space>
            </div>
            <div className="image-upload-grid">
              {visibleUploadFiles.map(file => (
                <UploadPreviewCard
                  key={file.uid}
                  file={file}
                  selected={selectedFileUidSet.has(file.uid)}
                  onToggle={() => setSelectedFileUids(current =>
                    current.includes(file.uid) ? current.filter(uid => uid !== file.uid) : [...current, file.uid]
                  )}
                  onRemove={() => {
                    setFiles(current => current.filter(item => item.uid !== file.uid));
                    setSelectedFileUids(current => current.filter(uid => uid !== file.uid));
                  }}
                />
              ))}
            </div>
            {files.length > uploadPreviewPageSize && (
              <Flex justify="center" style={{ marginTop: 16 }}>
                <Pagination
                  current={uploadPreviewPage}
                  pageSize={uploadPreviewPageSize}
                  total={files.length}
                  showSizeChanger={false}
                  onChange={setUploadPreviewPage}
                />
              </Flex>
            )}
          </>
        )}
        <div className="image-upload-actions">
          <Typography.Text type="secondary">只会上传已勾选的 {selectedFileUids.length} 张图片</Typography.Text>
          <Button type="primary" size="large" icon={<CheckCircleOutlined />} loading={uploading} disabled={categoryId == null || !selectedFileUids.length} onClick={start}>
            上传并开始检测
          </Button>
        </div>
      </ProCard>
    </Space>
  );

  const taskDetail = batch && (
    <Space direction="vertical" style={{ width: '100%' }} size="large">
      {['QUEUED', 'CHECKING', 'CONFIRMING'].includes(batch.status) && (
        <Alert
          type="info"
          showIcon
          message={batch.status === 'QUEUED' ? '任务正在排队' : '任务正在后台检测'}
          description="此窗口会每2秒自动刷新；关闭窗口或离开页面不会中断任务。"
        />
      )}
      <Progress percent={processedPercent} status={batch.status === 'FAILED' ? 'exception' : batch.status === 'COMPLETED' ? 'success' : 'active'} />
      <Space wrap>
        <Tag color="cyan">类目 {batch.categoryName}</Tag>
        <Tag color="geekblue">侵权检测 {infringementModeText[batch.infringementMode]}</Tag>
        <Tag>总数 {batch.totalCount}</Tag>
        <Tag color="blue">已处理 {batch.processedCount}</Tag>
        <Tag color="green">唯一 {batch.uniqueCount}</Tag>
        <Tag color="orange">重复 {batch.duplicateCount}</Tag>
        <Tag color="red">失败 {batch.failedCount}</Tag>
        <Tag color="purple">阈值 {(Number(batch.similarityThreshold) * 100).toFixed(0)}%</Tag>
        {batch.detectionCompletedAt && <Tag color="success">检测完成 {batch.detectionCompletedAt}</Tag>}
      </Space>
      {batch.errorMessage && <Alert type="error" showIcon message={batch.errorMessage} />}
      {failedImages.length > 0 && (
        <>
          <Alert
            type="error"
            showIcon
            message={`失败明细：${failedImages.length} 张图片需要处理`}
            description="检测或正式入库失败不会影响已成功入库的图片；请根据下方原因修复后再重试。"
          />
          <Table<ImageView>
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={failedImages}
            columns={[
              { title: '图片名称', dataIndex: 'originalName', width: 220, ellipsis: true },
              {
                title: '失败阶段', width: 110,
                render: (_, image) => image.status === 'APPROVAL_FAILED'
                  ? <Tag color="orange">正式入库</Tag>
                  : <Tag color="red">图片检测</Tag>
              },
              {
                title: '失败原因', dataIndex: 'errorMessage',
                render: reason => <Typography.Text type="danger">{reason || '未记录具体原因，请重试后查看'}</Typography.Text>
              }
            ]}
          />
        </>
      )}
      <Space>
        {batch.status === 'FAILED' && canRetry && <Button icon={<ReloadOutlined />} onClick={retry}>重试失败图片</Button>}
        {batch.status === 'WAIT_REVIEW' && (
          <Button type="primary" onClick={() => { setTaskDrawerOpen(false); history.push('/image/upload?tab=review'); }}>
            进入人工审核
          </Button>
        )}
      </Space>
    </Space>
  );

  const reviewPanel = (
    <ProCard
      title="待人工审核批次"
      extra={<Tag color="warning">{reviewBatches.length} 个批次待处理</Tag>}
      className="image-workflow-card"
    >
      {!reviewBatches.length ? (
        <Empty description="暂无待人工审核的批次" />
      ) : (
        <Collapse
          activeKey={activeReviewKeys}
          onChange={keys => {
            const next = (Array.isArray(keys) ? keys : [keys]).map(String);
            setActiveReviewKeys(next);
          }}
          items={reviewBatches.map(summary => {
            const key = String(summary.id);
            const detail = reviewDetails[key];
            const selectedIds = reviewSelectedIds[key] || [];
            return {
              key,
              label: (
                <Space wrap>
                  <Typography.Text strong>批次 ID：{key}</Typography.Text>
                  <Tag color="blue">创建人 {summary.creatorName}</Tag>
                  {batchStatusTag(summary.status)}
                  <Tag>共 {summary.totalCount} 张</Tag>
                  <Tag color="orange">重复 {summary.duplicateCount}</Tag>
                </Space>
              ),
              children: !detail ? (
                <Progress percent={60} showInfo={false} />
              ) : (
                <>
                  <Alert
                    type="info"
                    showIcon
                    message="仅显示达到相似阈值的图片；低于阈值的图片已自动入库。勾选后可保留当前待审图片。"
                    style={{ marginBottom: 16 }}
                  />
                  <List<ImageView>
                    className="image-review-list"
                    dataSource={detail.images.filter(image => image.status === 'WAIT_REVIEW')}
                    pagination={{
                      pageSize: 5,
                      hideOnSinglePage: true,
                      showSizeChanger: false,
                      showTotal: total => `共 ${total} 张待审图片`
                    }}
                    renderItem={image => (
                      <List.Item key={String(image.id)}>
                        <div className="image-review-comparison">
                          <div className="image-review-asset">
                            <Typography.Text strong>待审图片</Typography.Text>
                            <div className="image-review-asset__preview">
                              <AuthorizedImage url={image.thumbnailUrl} width={200} height={160} alt={`待审图片：${image.originalName}`} />
                            </div>
                            <Typography.Text ellipsis={{ tooltip: image.originalName }}>{image.originalName}</Typography.Text>
                            {statusTag(image.status)}
                          </div>
                          <div className="image-review-matches">
                            <Flex justify="space-between" align="center" wrap gap={8}>
                              <Typography.Text strong>当前图片的最相似 30 张</Typography.Text>
                              <Tag color="purple">共 {(image.similarImages || []).length} 张</Tag>
                            </Flex>
                            <SimilarImageGrid images={image.similarImages || []} />
                          </div>
                          <Flex className="image-review-decision" justify="space-between" align="center" gap={16} wrap>
                            <Space wrap>
                              {image.matchMethod === 'EXACT_HASH' && <Tag color="red">SHA-256完全重复</Tag>}
                              {image.matchMethod === 'SSCD' && <Tag color={image.duplicate ? 'orange' : 'green'}>SSCD相似度</Tag>}
                              {image.similarity != null && (
                                <Typography.Text strong>{(Number(image.similarity) * 100).toFixed(2)}%</Typography.Text>
                              )}
                              {image.duplicate && <Typography.Text type="danger">达到重复阈值</Typography.Text>}
                              {isAdmissionProhibited(image) && <Tag color="red">超过95%，禁止入库</Tag>}
                            </Space>
                            <Checkbox
                              checked={!isAdmissionProhibited(image) && selectedIds.map(String).includes(String(image.id))}
                              disabled={!canConfirm || isAdmissionProhibited(image) || image.status === 'FAILED' || image.status === 'REJECTED' || image.status === 'APPROVED'}
                              onChange={event => {
                                setReviewSelectedIds(current => {
                                  const selected = current[String(detail.id)] || [];
                                  return {
                                    ...current,
                                    [String(detail.id)]: event.target.checked
                                      ? [...selected, image.id]
                                      : selected.filter(id => String(id) !== String(image.id))
                                  };
                                });
                              }}
                            >
                              {isAdmissionProhibited(image) ? '禁止入库' : '保留并入库'}
                            </Checkbox>
                          </Flex>
                        </div>
                      </List.Item>
                    )}
                  />
                  <Button
                    type="primary"
                    loading={confirming}
                    disabled={!canConfirm}
                    onClick={() => void confirmReviewBatch(detail.id)}
                    style={{ marginTop: 16 }}
                  >
                    确认入库（{selectedIds.length}张）
                  </Button>
                </>
              )
            };
          })}
        />
      )}
    </ProCard>
  );

  const libraryPanel = (
    <ProCard
      title="图片资产库"
      extra={<Space><Typography.Text type="secondary">共 {libraryTotal} 张</Typography.Text>{canRemove && <Button danger icon={<DeleteOutlined />}
        onClick={() => setClearLibraryOpen(true)}>一键清空</Button>}<Button icon={<DownloadOutlined />}
        disabled={!libraryTotal}
        onClick={() => void exportFile('/images/export', '正式图库图片.zip', {
          ...libraryFiltersRef.current,
          keyword: keyword || undefined,
          categoryId: libraryCategoryId,
          batchId: libraryBatchId || undefined,
          aspectRatio: libraryAspectRatio
        }).then(() => loadLibrary(libraryPage, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio))}>导出筛选结果</Button>
        {canRemove && <Button onClick={async () => { const result = await listImageReplacementLogs(); setReplacementLogs(result.data); setReplacementLogsOpen(true); }}>替换日志</Button>}
        </Space>}
      className="image-workflow-card"
      loading={libraryLoading}
    >
      {!canRemove && <Alert type="info" showIcon message="仅展示本人上传的图片；只能导出检测成功、低风险且尚未导出过的图片。" style={{ marginBottom: 16 }} />}
      <div className="image-library-filters">
        <Input allowClear placeholder="图片 ID" aria-label="图片 ID" value={libraryFilters.imageId} style={{ width: 210 }}
          onChange={event => updateLibraryFilters({ imageId: event.target.value.replace(/\D/g, '') || undefined })} />
        {canRemove && <Input allowClear placeholder="上传用户名" aria-label="上传用户名" value={libraryFilters.userName} style={{ width: 180 }}
          onChange={event => updateLibraryFilters({ userName: event.target.value || undefined })} />}
        <Select allowClear placeholder="风险等级" aria-label="风险等级" value={libraryFilters.riskLevel} style={{ width: 150 }}
          options={[{ label: '低风险', value: 'LOW' }, { label: '高风险', value: 'HIGH' }, { label: '待核查', value: 'NEEDS_REVIEW' }]}
          onChange={value => updateLibraryFilters({ riskLevel: value })} />
        <DatePicker.RangePicker showTime placeholder={['上传开始时间', '上传结束时间']} onChange={values => updateLibraryFilters({
          uploadStart: values?.[0]?.format('YYYY-MM-DD HH:mm:ss'), uploadEnd: values?.[1]?.format('YYYY-MM-DD HH:mm:ss')
        })} />
        <Button type="primary" onClick={() => void loadLibrary(1, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio)}>查询</Button>
        <Input.Search
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索文件名"
          value={keyword}
          onChange={event => setKeyword(event.target.value)}
          onSearch={value => void loadLibrary(1, libraryPageSize, value, libraryCategoryId, libraryBatchId, libraryAspectRatio)}
          style={{ width: 300 }}
        />
        <Input.Search
          allowClear
          placeholder="搜索批次 ID"
          value={libraryBatchId}
          onChange={event => setLibraryBatchId(event.target.value.replace(/\D/g, ''))}
          onSearch={value => void loadLibrary(1, libraryPageSize, keyword, libraryCategoryId, value.replace(/\D/g, ''), libraryAspectRatio)}
          style={{ width: 260 }}
        />
        <Select
          allowClear
          placeholder="全部类目"
          value={libraryCategoryId}
          options={categories.map(category => ({ value: category.id, label: category.name }))}
          style={{ width: 180 }}
          onChange={value => {
            setLibraryCategoryId(value);
            void loadLibrary(1, libraryPageSize, keyword, value, libraryBatchId, libraryAspectRatio);
          }}
        />
        <Select
          allowClear
          placeholder="全部比例"
          value={libraryAspectRatio}
          options={aspectRatioOptions}
          style={{ width: 150 }}
          onChange={value => {
            setLibraryAspectRatio(value);
            void loadLibrary(1, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, value);
          }}
        />
        <Button icon={<ReloadOutlined />} onClick={() => void loadLibrary(libraryPage, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio)}>刷新</Button>
      </div>
      {library.length ? (
        <>
          <div className="image-library-grid">
            {library.map(image => (
              <article className={`image-library-card${image.vectorIndexMissing ? ' is-index-abnormal' : ''}`} key={String(image.id)}>
                <div className="image-library-card__preview">
                  <AuthorizedImage url={image.thumbnailUrl} width={240} height={168} />
                  <Tag color="blue">{image.categoryName}</Tag>
                </div>
                <div className="image-library-card__body">
                  <Typography.Text strong ellipsis={{ tooltip: image.displayName }}>
                    {image.displayName}
                  </Typography.Text>
                  <div>{infringementTag(image)}</div>
                  {image.vectorIndexMissing && (
                    <Flex justify="space-between" align="center" gap={8}>
                      <Tag color="error">向量索引异常</Tag>
                      {canRemove && (
                        <Popconfirm title="将核对并修复正式图库向量索引，是否继续？" onConfirm={() => void repairVectorIndex()}>
                          <Button danger size="small" type="link">修复</Button>
                        </Popconfirm>
                      )}
                    </Flex>
                  )}
                  <Typography.Text type="secondary">图片 ID {String(image.id)} · 上传人 {image.uploaderName}</Typography.Text>
                  {!canRemove && image.exported && <Tag>已导出</Tag>}
                  <Typography.Text type="secondary">批次 {String(image.batchId)} · 比例 {image.aspectRatio}</Typography.Text>
                  <Typography.Text type="secondary">入库时间 {image.approvedAt || '-'}</Typography.Text>
                  <Flex justify="space-between" align="center">
                    <Button
                      type="link"
                      icon={<InfoCircleOutlined />}
                      onClick={async () => {
                        const response = await getApprovedImage(image.id);
                        setDetailImage(response.data);
                      }}
                    >
                      查看详情
                    </Button>
                    {canRemove && <Upload accept="image/jpeg,image/png,image/webp" maxCount={1} multiple={false} showUploadList={false}
                      beforeUpload={async file => {
                        if (!isSupportedImageFile(file.name)) { message.warning('请选择 JPG、PNG 或 WebP 图片'); return Upload.LIST_IGNORE; }
                        setReplacingImageId(image.id);
                        try {
                          const storageMode = (await getImageStorageMode()).data;
                          let batchId: ImageId;
                          if (storageMode.directUpload) {
                            const prepared = (await prepareImageReplacement(image.id, file)).data;
                            const target = prepared.upload.files[0];
                            try {
                              await postImageToOss(prepared.upload, target, file, () => undefined);
                            } catch (error) {
                              await completeImageUploads(prepared.batchId, [], [target.imageId]).catch(() => undefined);
                              throw error;
                            }
                            await completeImageUploads(prepared.batchId, [target.imageId]);
                            await startImageBatch(prepared.batchId);
                            batchId = prepared.batchId;
                          } else {
                            batchId = (await replaceApprovedImage(image.id, file)).data.id;
                          }
                          message.success(`替换任务已提交，批次 ${String(batchId)}。新图入库成功后会归档旧图。`);
                        } finally { setReplacingImageId(undefined); }
                        return false;
                      }}><Button type="link" loading={String(replacingImageId) === String(image.id)} disabled={replacingImageId != null}>替换</Button></Upload>}
                    {canRemove && (
                      <Popconfirm
                        title="确认从正式图库删除？文件将在7天后清理。"
                        onConfirm={async () => {
                          await deleteApprovedImage(image.id);
                          message.success('已移出正式图库');
                          await Promise.all([
                            loadLibrary(libraryPage, libraryPageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio),
                            loadHealth(),
                            loadAspectRatios()
                          ]);
                        }}
                      >
                        <Button danger type="link" icon={<DeleteOutlined />}>删除</Button>
                      </Popconfirm>
                    )}
                  </Flex>
                </div>
              </article>
            ))}
          </div>
          <Pagination
            current={libraryPage}
            pageSize={libraryPageSize}
            total={libraryTotal}
            showSizeChanger
            className="image-library-pagination"
            onChange={(page, pageSize) => void loadLibrary(page, pageSize, keyword, libraryCategoryId, libraryBatchId, libraryAspectRatio)}
          />
        </>
      ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无正式图库图片" />}
    </ProCard>
  );

  const taskPanel = (
    <ProCard
      title="上传批次"
      extra={<Button icon={<ReloadOutlined />} onClick={() => void loadTasks(taskPage, taskPageSize)}>刷新</Button>}
      className="image-workflow-card"
    >
      <Alert type="info" showIcon message="检测在服务器后台执行，离开页面不会中断。" style={{ marginBottom: 16 }} />
      <Table<BatchSummary>
        rowKey={row => String(row.id)}
        columns={taskColumns}
        dataSource={tasks}
        loading={taskLoading}
        pagination={{
          current: taskPage,
          pageSize: taskPageSize,
          total: taskTotal,
          showSizeChanger: true,
          onChange: (page, pageSize) => void loadTasks(page, pageSize)
        }}
      />
    </ProCard>
  );

  return (
    <PageContainer title={pageTitle}>
      <div className="image-management-page">
        <Flex className="image-management-heading" justify="space-between" align="center" gap={16} wrap>
          <div>
            <Typography.Title level={3}>{pageTitle}</Typography.Title>
            <Typography.Text type="secondary">{pageDescriptions[pageKey]}</Typography.Text>
          </div>
          <Space>
            <Badge status={health?.status === 'UP' ? 'success' : 'error'} text={health?.status === 'UP' ? '图片服务正常' : '图片服务异常'} />
            {pageKey !== 'upload' && (
              <Button type="primary" onClick={() => history.push('/image/upload')}>新建上传批次</Button>
            )}
          </Space>
        </Flex>
        {pageKey !== 'library' && (
          <Tabs
            className="image-management-tabs"
            activeKey={pageKey}
            centered
            items={availableWorkflowTabs}
            onChange={key => {
              const target = availableWorkflowTabs.find(tab => tab.key === key);
              if (target) history.push(target.path);
            }}
          />
        )}
        {pageKey !== 'library' && !availableWorkflowTabs.length && <Empty description="暂无图片工作流操作权限" />}
        {pageKey === 'upload' && canUpload && uploadPanel}
        {pageKey === 'tasks' && canQuery && taskPanel}
        {pageKey === 'review' && canQuery && reviewPanel}
        {pageKey === 'library' && libraryPanel}
      </div>
      <Drawer
        title={batch ? `上传进度：批次 ${String(batch.id)}` : '上传进度'}
        open={taskDrawerOpen}
        width={560}
        onClose={() => setTaskDrawerOpen(false)}
      >
        {taskDetail}
      </Drawer>
      <Modal open={replacementLogsOpen} title="图片替换日志" width={1100} footer={null} onCancel={() => setReplacementLogsOpen(false)}>
        <Table<ImageReplacementLog> rowKey={row => String(row.id)} dataSource={replacementLogs} scroll={{ x: 1000 }} columns={[
          { title: '操作人', dataIndex: 'userName' }, { title: '时间', dataIndex: 'createTime' },
          { title: '新图 ID', dataIndex: 'newImageId', render: id => <Button type="link" onClick={() => { updateLibraryFilters({ imageId: id }); setReplacementLogsOpen(false); void loadLibrary(1, libraryPageSize, '', undefined, undefined, undefined); }}>{String(id)}</Button> },
          { title: '旧图 ID', dataIndex: 'oldImageId' }, { title: '旧图 OSS 路径', dataIndex: 'oldStorageKey', ellipsis: true }
        ]} />
      </Modal>
      <Modal
        open={clearLibraryOpen}
        title="清空图库"
        okText="清空图库"
        okButtonProps={{ danger: true }}
        confirmLoading={clearingLibrary}
        destroyOnHidden
        closable={!clearingLibrary}
        keyboard={!clearingLibrary}
        onOk={() => void clearLibrary()}
        onCancel={() => {
          if (!clearingLibrary) {
            setClearLibraryOpen(false);
          }
        }}
      >
        <Typography.Paragraph type="danger">
          此操作会永久删除所有图片、批次、侵权扫描记录、关联文件和向量索引，图片类目及其他系统数据不会删除。
        </Typography.Paragraph>
      </Modal>
      <Modal
        open={Boolean(detailImage)}
        title={detailImage?.displayName || '图片详情'}
        width={720}
        footer={canRemove && detailImage?.infringementStatus === 'FAILED'
          ? <Button loading={rescanningImage} onClick={() => void rescanImage()}>重新检测</Button>
          : null}
        onCancel={() => setDetailImage(undefined)}
      >
        {detailImage && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <div style={{ textAlign: 'center' }}>
              <AuthorizedImage url={detailImage.originalUrl} width={560} height={360} />
            </div>
            <Descriptions column={2} bordered size="small">
              <Descriptions.Item label="图片ID">{String(detailImage.id)}</Descriptions.Item>
              <Descriptions.Item label="状态">{statusTag(detailImage.status)}</Descriptions.Item>
              <Descriptions.Item label="类目">{detailImage.categoryName}</Descriptions.Item>
              <Descriptions.Item label="原文件名">{detailImage.originalName}</Descriptions.Item>
              <Descriptions.Item label="格式">{detailImage.contentType}</Descriptions.Item>
              <Descriptions.Item label="文件大小">{(detailImage.fileSize / 1024).toFixed(1)} KB</Descriptions.Item>
              <Descriptions.Item label="上传人">{detailImage.uploaderName}</Descriptions.Item>
              <Descriptions.Item label="上传时间">{detailImage.createTime || '-'}</Descriptions.Item>
              <Descriptions.Item label="入库时间">{detailImage.approvedAt || '-'}</Descriptions.Item>
              <Descriptions.Item label="侵权检测">{infringementTag(detailImage)}</Descriptions.Item>
              <Descriptions.Item label="TRO 案号">{detailImage.infringementCaseNumbers?.join(', ') || '-'}</Descriptions.Item>
              {(['subjects', 'scenes', 'themes', 'visualStyles', 'colors'] as const).map(field => (
                <Descriptions.Item key={field} label={field} span={2}>{detailImage[field]?.join(', ') || '-'}</Descriptions.Item>
              ))}
              {detailImage.infringementStatus === 'SUCCESS' && detailImage.infringementRiskReason && (
                <Descriptions.Item label="风险原因" span={2}>{detailImage.infringementRiskReason}</Descriptions.Item>
              )}
              {detailImage.infringementErrorMessage && (
                <Descriptions.Item label="检测错误" span={2}>{detailImage.infringementErrorMessage}</Descriptions.Item>
              )}
            </Descriptions>
          </Space>
        )}
      </Modal>
    </PageContainer>
  );
}
