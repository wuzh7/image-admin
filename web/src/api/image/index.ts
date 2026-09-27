import type { PageResult, R } from '@/api/types';
import request from '@/api/request';
import type {
  BatchSummary,
  BatchView,
  HealthView,
  ImageCategory,
  ImageId,
  ImageQuery,
  ImageView,
  ImageReplacementLog,
  ImageDuplicateTodo,
  InfringementItemStatus,
  InfringementMode,
  InfringementItemView,
  InfringementRiskLevel,
  InfringementTaskSummary,
  InfringementCallLogView,
  InfringementCallResponseView,
  RetryInfringementResult,
  SaveImageCategory,
  ImageResetResult,
  VectorRepairView
} from './types';

export function createImageBatch(data: { similarityThreshold: number; categoryId: ImageId; infringementMode: InfringementMode }) {
  return request<R<BatchView>>({ url: '/image/batches', method: 'post', data });
}

export function getInfringementCallResponse(taskId: ImageId, callId: ImageId) {
  return request<R<InfringementCallResponseView>>({
    url: `/image/infringement/tasks/${taskId}/calls/${callId}/response`, method: 'get'
  });
}

export function uploadBatchFiles(batchId: ImageId, files: File[]) {
  const data = new FormData();
  files.forEach(file => data.append('files', file));
  return request<R<number>>({
    url: `/image/batches/${batchId}/files`,
    method: 'post',
    data,
    headers: { repeatSubmit: false }
  });
}

export function startImageBatch(batchId: ImageId) {
  return request<R>({ url: `/image/batches/${batchId}/start`, method: 'post' });
}

export function getImageBatch(batchId: ImageId) {
  return request<R<BatchView>>({ url: `/image/batches/${batchId}`, method: 'get' });
}

export function getImageBatchSummary(batchId: ImageId) {
  return request<R<BatchSummary>>({ url: `/image/batches/${batchId}/summary`, method: 'get' });
}

export function listImageReviewImages(batchId: ImageId, params: { pageNum: number; pageSize: number }) {
  return request<R<PageResult<ImageView>>>({
    url: `/image/batches/${batchId}/review-images`, method: 'get', params
  });
}

export function listImageFailedImages(batchId: ImageId, params: { pageNum: number; pageSize: number }) {
  return request<R<PageResult<ImageView>>>({
    url: `/image/batches/${batchId}/failed-images`, method: 'get', params
  });
}

export function listImageBatches(params: { pageNum: number; pageSize: number; status?: BatchSummary['status']; userName?: string }) {
  return request<R<PageResult<BatchSummary>>>({ url: '/image/batches', method: 'get', params });
}

export function confirmImageBatch(batchId: ImageId, approvedIds: ImageId[]) {
  return request<R>({ url: `/image/batches/${batchId}/confirm`, method: 'post', data: { approvedIds } });
}

export function retryImageBatch(batchId: ImageId) {
  return request<R>({ url: `/image/batches/${batchId}/retry`, method: 'post' });
}

export function listApprovedImages(params: ImageQuery) {
  return request<R<PageResult<ImageView>>>({ url: '/images', method: 'get', params });
}

export function replaceApprovedImage(imageId: ImageId, file: File) {
  const data = new FormData(); data.append('file', file);
  return request<R<BatchView>>({ url: `/images/${imageId}/replace`, method: 'post', data });
}

export function prepareImageReplacement(imageId: ImageId, file: File) {
  return request<R<{ batchId: ImageId; upload: UploadSession }>>({
    url: `/images/${imageId}/replacement-session`, method: 'post',
    headers: { repeatSubmit: false },
    data: { files: [{
      name: file.name, size: file.size,
      contentType: file.type || (/\.png$/i.test(file.name) ? 'image/png' : /\.webp$/i.test(file.name) ? 'image/webp' : 'image/jpeg')
    }] }
  });
}

export function listImageReplacementLogs(userName?: string) {
  return request<R<ImageReplacementLog[]>>({ url: '/images/replacement-logs', method: 'get', params: { userName } });
}

export function listImageDuplicateTodos(userName?: string) {
  return request<R<ImageDuplicateTodo[]>>({ url: '/image/duplicate-todos', method: 'get', params: { userName } });
}

export function completeImageDuplicateTodo(id: ImageId) {
  return request<R>({ url: `/image/duplicate-todos/${id}/complete`, method: 'post' });
}

export function getApprovedImage(imageId: ImageId) {
  return request<R<ImageView>>({ url: `/images/${imageId}`, method: 'get' });
}

export function deleteApprovedImage(imageId: ImageId) {
    return request<R>({ url: `/images/${imageId}`, method: 'delete' });
}

export function resetImageData() {
  return request<R<ImageResetResult>>({ url: '/images/reset', method: 'delete' });
}

export function getImageContent(url: string) {
  return request<Blob>({ url, method: 'get', responseType: 'blob' });
}

export function getImageHealth() {
  return request<R<HealthView>>({ url: '/image/health', method: 'get' });
}

export function repairImageVectorIndex() {
  return request<R<VectorRepairView>>({ url: '/image/vector-index/repair', method: 'post' });
}

export function listImageAspectRatios() {
  return request<R<string[]>>({ url: '/images/aspect-ratios', method: 'get' });
}

export function getImageCategoryOptions() {
  return request<R<ImageCategory[]>>({ url: '/image/categories/options', method: 'get' });
}

export function listImageCategories() {
  return request<R<ImageCategory[]>>({ url: '/image/categories', method: 'get' });
}

export function createImageCategory(data: SaveImageCategory) {
  return request<R<ImageCategory>>({ url: '/image/categories', method: 'post', data });
}

export function updateImageCategory(id: ImageId, data: SaveImageCategory) {
  return request<R<ImageCategory>>({ url: `/image/categories/${id}`, method: 'put', data });
}

export function deleteImageCategory(id: ImageId) {
  return request<R>({ url: `/image/categories/${id}`, method: 'delete' });
}
export interface UploadTarget {
  imageId: ImageId;
  objectKey: string;
  contentType: string;
  size: number;
}

export interface UploadSession {
  host: string;
  keyPrefix: string;
  formFields: Record<string, string>;
  expiresAt: string;
  uploadConcurrency: number;
  files: UploadTarget[];
}

export function getImageStorageMode() {
  return request<R<{ directUpload: boolean; uploadConcurrency: number; similarityThreshold: number | string }>>({ url: '/image/storage', method: 'get' });
}

export function prepareImageUploads(batchId: ImageId, files: File[]) {
  return request<R<UploadSession>>({
    url: `/image/batches/${batchId}/upload-session`, method: 'post',
    headers: { repeatSubmit: false },
    data: { files: files.map(file => ({
      name: file.name, size: file.size,
      contentType: file.type || (/\.png$/i.test(file.name) ? 'image/png' : /\.webp$/i.test(file.name) ? 'image/webp' : 'image/jpeg')
    })) }
  });
}

export function completeImageUploads(batchId: ImageId, imageIds: ImageId[], failedIds: ImageId[] = []) {
  return request<R>({ url: `/image/batches/${batchId}/upload-complete`, method: 'post',
    data: { imageIds, failedIds }, headers: { repeatSubmit: false } });
}

// 使用独立 XHR 上传，既不泄露业务 Token，也能统计真实字节进度。file 必须是表单最后一项。
export function postImageToOss(session: UploadSession, target: UploadTarget, file: File,
  onProgress: (loaded: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const data = new FormData();
    data.append('key', target.objectKey);
    Object.entries(session.formFields).forEach(([key, value]) => data.append(key, value));
    data.append('Content-Type', target.contentType);
    data.append('file', file);
    const xhr = new XMLHttpRequest();
    xhr.open('POST', session.host);
    xhr.timeout = 5 * 60 * 1000;
    xhr.withCredentials = false;
    xhr.upload.onprogress = event => onProgress(event.lengthComputable ? event.loaded : 0);
    xhr.onload = () => xhr.status >= 200 && xhr.status < 300
      ? resolve()
      : reject(new Error(`图片直传失败（HTTP ${xhr.status}）`));
    xhr.onerror = () => reject(new Error('图片直传网络异常'));
    xhr.ontimeout = () => reject(new Error('图片直传超时'));
    xhr.send(data);
  });
}

export function getImageAccess(url: string) {
  return request<R<string>>({ url: url.replace('/content?', '/access?'), method: 'get' });
}

export function listInfringementTasks(params: { pageNum?: number; pageSize?: number }) {
  return request<R<PageResult<InfringementTaskSummary>>>({
    url: '/image/infringement/tasks', method: 'get', params
  });
}

export function listInfringementItems(taskId: ImageId, params: {
  pageNum?: number;
  pageSize?: number;
  status?: InfringementItemStatus;
  riskLevel?: InfringementRiskLevel;
}) {
  return request<R<PageResult<InfringementItemView>>>({
    url: `/image/infringement/tasks/${taskId}/items`, method: 'get', params
  });
}

export function listInfringementCallLogs(taskId: ImageId, params: {
  pageNum?: number;
  pageSize?: number;
  imageId?: ImageId;
  status?: 'SUCCESS' | 'FAILED';
  executionMode?: 'RELAY' | 'OFFICIAL_STANDARD' | 'OFFICIAL_BATCH';
}) {
  return request<R<PageResult<InfringementCallLogView>>>({
    url: `/image/infringement/tasks/${taskId}/calls`, method: 'get', params
  });
}

export function retryFailedInfringementTask(taskId: ImageId) {
  return request<R<RetryInfringementResult>>({
    url: `/image/infringement/tasks/${taskId}/retry-failed`, method: 'post'
  });
}

export function rescanImageInfringement(imageId: ImageId) {
  return request<R<RetryInfringementResult>>({
    url: `/image/infringement/images/${imageId}/rescan`, method: 'post'
  });
}
