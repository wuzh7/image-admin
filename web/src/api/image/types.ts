import type { PageQuery } from '@/api/types';

export type ImageId = string | number;
export type BatchStatus = 'UPLOADING' | 'QUEUED' | 'CHECKING' | 'WAIT_REVIEW' | 'CONFIRMING' | 'COMPLETED' | 'FAILED';
export type ImageStatus = 'UPLOADING' | 'APPROVING' | 'APPROVAL_FAILED' | 'DELETE_PENDING' | 'UPLOADED' | 'CHECKING' | 'WAIT_REVIEW' | 'APPROVED' | 'REJECTED' | 'FAILED';

export interface ImageCategory {
  id: ImageId;
  name: string;
  sortOrder: number;
  status: '0' | '1';
}

export interface SaveImageCategory {
  name: string;
  sortOrder: number;
  status: '0' | '1';
}

export interface ImageView {
  id: ImageId;
  batchId: ImageId;
  categoryId?: ImageId;
  categoryName: string;
  originalName: string;
  displayName: string;
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
  matchMethod?: 'EXACT_HASH' | 'SSCD';
  thumbnailUrl: string;
  originalUrl: string;
  vectorIndexMissing: boolean;
  errorMessage?: string;
  createTime?: string;
}

export interface BatchView {
  id: ImageId;
  categoryId?: ImageId;
  categoryName: string;
  status: BatchStatus;
  similarityThreshold: number | string;
  totalCount: number;
  processedCount: number;
  uniqueCount: number;
  duplicateCount: number;
  failedCount: number;
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

export interface ImageQuery extends PageQuery {
  keyword?: string;
  categoryId?: ImageId;
  batchId?: ImageId;
  aspectRatio?: string;
}
