import type { PageQuery } from '@/api/types';

export type ImageId = string | number;
export type BatchStatus = 'UPLOADING' | 'QUEUED' | 'CHECKING' | 'WAIT_REVIEW' | 'CONFIRMING' | 'COMPLETED' | 'FAILED';
export type ImageStatus = 'UPLOADING' | 'APPROVING' | 'APPROVAL_FAILED' | 'DELETE_PENDING' | 'UPLOADED' | 'CHECKING' | 'WAIT_REVIEW' | 'APPROVED' | 'REJECTED' | 'REPLACED' | 'FAILED';
export type InfringementMode = 'RELAY' | 'OFFICIAL_HYBRID' | 'OFFICIAL_STANDARD' | 'OFFICIAL_BATCH' | 'OFFICIAL_FLEX';

export interface ImageCategory {
  id: ImageId;
  name: string;
  sortOrder?: number;
  status: '0' | '1';
}

export interface SaveImageCategory {
  name: string;
  status: '0' | '1';
}

export interface ImageView {
  id: ImageId;
  batchId: ImageId;
  categoryId?: ImageId;
  categoryName: string;
  originalName: string;
  displayName: string;
  infringementDescriptionKeywords?: string;
  contentType: string;
  fileSize: number;
  width: number;
  height: number;
  aspectRatio: string;
  status: ImageStatus;
  duplicate?: boolean;
  duplicateOf?: ImageId;
  duplicateName?: string;
  duplicateThumbnailUrl?: string;
  matchSource?: 'BATCH' | 'LIBRARY';
  similarity?: number | string;
  similarImages: SimilarImageView[];
  matchMethod?: 'EXACT_HASH' | 'SSCD';
  thumbnailUrl: string;
  originalUrl: string;
  vectorIndexMissing: boolean;
  errorMessage?: string;
  approvedAt?: string;
  infringementStatus?: InfringementItemStatus;
  infringementRiskLevel?: InfringementRiskLevel;
  infringementRiskReason?: string;
  infringementCaseNumbers?: string[];
  infringementErrorMessage?: string;
  renderUseCount: number;
  createTime?: string;
  uploaderName: string;
  subjects: string[];
  scenes: string[];
  themes: string[];
  visualStyles: string[];
  colors: string[];
  exported: boolean;
}

export interface SimilarImageView {
  imageId: ImageId;
  name: string;
  thumbnailUrl: string;
  source: 'BATCH' | 'LIBRARY';
  similarity: number | string;
}

export interface BatchView {
  id: ImageId;
  categoryId?: ImageId;
  categoryName: string;
  status: BatchStatus;
  infringementMode: InfringementMode;
  similarityThreshold: number | string;
  totalCount: number;
  processedCount: number;
  uniqueCount: number;
  duplicateCount: number;
  failedCount: number;
  detectionCompletedAt?: string;
  errorMessage?: string;
  createTime?: string;
  images: ImageView[];
}

export interface BatchSummary extends Omit<BatchView, 'images'> {
  creatorName: string;
}

export interface HealthView {
  status: 'UP' | 'DOWN';
  modelVersion: string;
  modelSha256?: string;
  modelReady: boolean;
  qdrantCollection: string;
  qdrantPoints: number;
  approvedImageCount: number;
  indexCountMatched: boolean;
}

export interface VectorRepairView {
  approvedImageCount: number;
  qdrantPointsBefore: number;
  restoredPoints: number;
  removedPoints: number;
}

export interface ImageResetResult {
  batches: number;
  assets: number;
  tasks: number;
  items: number;
}

export interface ImageQuery extends PageQuery {
  keyword?: string;
  categoryId?: ImageId;
  batchId?: ImageId;
  aspectRatio?: string;
  imageId?: ImageId;
  userName?: string;
  riskLevel?: InfringementRiskLevel;
  uploadStart?: string;
  uploadEnd?: string;
}

export interface ImageReplacementLog {
  id: ImageId;
  oldImageId: ImageId;
  newImageId: ImageId;
  userId: ImageId;
  userName: string;
  oldStorageKey: string;
  newStorageKey: string;
  createTime: string;
}

export interface ImageDuplicateTodo {
  id: ImageId;
  imageId: ImageId;
  matchedImageId: ImageId;
  similarity: number | string;
  scanDate: string;
  userName: string;
  matchedUserName: string;
  completed: boolean;
  createTime: string;
}

export type InfringementItemStatus = 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED';
export type InfringementRiskLevel = 'HIGH' | 'LOW' | 'NEEDS_REVIEW';
export type InfringementTaskStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'PARTIAL_FAILED' | 'FAILED';

export interface InfringementTaskSummary {
  id: ImageId;
  taskNo: string;
  taskType: 'INITIAL' | 'RETRY_FAILED' | 'MANUAL_RESCAN';
  sourceBatchId?: ImageId;
  creatorName: string;
  infringementMode: InfringementMode;
  status: InfringementTaskStatus;
  totalCount: number;
  completedCount: number;
  highRiskCount: number;
  lowRiskCount: number;
  needsReviewCount: number;
  failedCount: number;
  createTime?: string;
}

export interface InfringementItemView {
  id: ImageId;
  imageId: ImageId;
  imageName: string;
  thumbnailUrl: string;
  imageStatus: ImageStatus;
  status: InfringementItemStatus;
  riskLevel?: InfringementRiskLevel;
  riskReason?: string;
  caseNumbers?: string[];
  confidence?: number;
  detectedElements: string[];
  imageDescription?: string;
  model?: string;
  promptVersion?: string;
  executionMode?: 'RELAY' | 'OFFICIAL_STANDARD' | 'OFFICIAL_BATCH';
  searchUsed?: boolean;
  searchQueryCount?: number;
  attemptCount: number;
  errorMessage?: string;
  finishedAt?: string;
  subjects: string[];
  scenes: string[];
  themes: string[];
  visualStyles: string[];
  colors: string[];
}

export interface RetryInfringementResult {
  taskId?: ImageId;
  imageCount: number;
}

export interface InfringementCallLogView {
  id: ImageId;
  taskId: ImageId;
  scanItemId: ImageId;
  batchId?: ImageId;
  imageId: ImageId;
  imageName?: string;
  requestId?: string;
  providerJobId?: string;
  providerRequestIndex?: number;
  executionMode?: 'RELAY' | 'OFFICIAL_STANDARD' | 'OFFICIAL_BATCH';
  model?: string;
  status: 'SUCCESS' | 'FAILED';
  riskLevel?: InfringementRiskLevel;
  callType?: 'RISK_SCAN' | 'CASE_LOOKUP';
  rawAiResponse?: string;
  usageMetadata?: string;
  promptTokenCount?: number;
  cachedContentTokenCount?: number;
  candidatesTokenCount?: number;
  thoughtsTokenCount?: number;
  toolUsePromptTokenCount?: number;
  totalTokenCount?: number;
  searchUsed?: boolean;
  searchQueryCount?: number;
  durationMs?: number;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface InfringementCallResponseView {
  id: ImageId;
  rawProviderResponse?: string;
}
