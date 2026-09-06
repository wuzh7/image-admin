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
  SaveImageCategory,
  VectorRepairView
} from './types';

export function createImageBatch(data: { similarityThreshold: number; categoryId: ImageId }) {
  return request<R<BatchView>>({ url: '/image/batches', method: 'post', data });
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

export function listImageBatches(params: { pageNum: number; pageSize: number; status?: BatchSummary['status'] }) {
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

export function getApprovedImage(imageId: ImageId) {
  return request<R<ImageView>>({ url: `/images/${imageId}`, method: 'get' });
}

export function deleteApprovedImage(imageId: ImageId) {
  return request<R>({ url: `/images/${imageId}`, method: 'delete' });
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
export interface UploadTicket {
  imageId: ImageId;
  uploadUrl: string;
  headers: Record<string, string>;
}

export function getImageStorageMode() {
  return request<R<boolean>>({ url: '/image/storage', method: 'get' });
}

export function prepareImageUploads(batchId: ImageId, files: File[]) {
  return request<R<UploadTicket[]>>({
    url: `/image/batches/${batchId}/upload-tickets`, method: 'post',
    headers: { repeatSubmit: false },
    data: { files: files.map(file => ({
      name: file.name, size: file.size,
      contentType: file.type || (/\.png$/i.test(file.name) ? 'image/png' : /\.webp$/i.test(file.name) ? 'image/webp' : 'image/jpeg')
    })) }
  });
}

export function completeImageUploads(batchId: ImageId, imageIds: ImageId[]) {
  return request<R>({ url: `/image/batches/${batchId}/upload-complete`, method: 'post',
    data: { imageIds }, headers: { repeatSubmit: false } });
}

// 使用独立请求，业务 Token、clientid、加密头都不能发送到 OSS。
export async function putImageToOss(ticket: UploadTicket, file: File) {
  const response = await fetch(ticket.uploadUrl, { method: 'PUT', headers: ticket.headers, body: file,
    credentials: 'omit', signal: AbortSignal.timeout(5 * 60 * 1000) });
  if (!response.ok) throw new Error(`图片直传失败（HTTP ${response.status}），请检查网络或上传地址是否过期`);
}

export function getImageAccess(url: string) {
  return request<R<string>>({ url: url.replace('/content?', '/access?'), method: 'get' });
}
