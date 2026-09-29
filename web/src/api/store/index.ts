import request from '@/api/request';
import type { R } from '@/api/types';
import type { ImageId } from '@/api/image/types';

export type StoreId = ImageId;
export interface StoreShop { id: StoreId; name: string; platform?: string; shopCode?: string; configJson?: string; }
export interface CreateStoreShop { name: string; shopCode: string; operatorUserId: StoreId; platformId: StoreId; siteIds: StoreId[]; categoryIds: StoreId[]; notes: string; }
export interface StoreTemplate {
  id: StoreId;
  name: string;
  ossKey: string;
  imageUrl?: string;
  templateConfigJson: string;
  width: number;
  height: number;
}
export interface StoreTemplateGroup {
  id: StoreId;
  name: string;
  categoryId: StoreId;
  calibrationConfigJson: string;
  maskOssKey?: string;
  status: 'ACTIVE' | 'INACTIVE' | 'DELETED';
  templates: StoreTemplate[];
  createTime?: string;
}
export interface StoreCalibration {
  id: StoreId;
  name: string;
  categoryId: StoreId;
  calibrationConfigJson: string;
  backgroundOssKey: string;
  backgroundUrl: string;
  maskOssKey?: string;
  maskUrl?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DELETED';
  version: number;
}
export type SaveCalibration = Omit<StoreCalibration, 'id' | 'backgroundUrl' | 'maskUrl' | 'status' | 'version'>;
export type SaveTemplateGroup = Omit<StoreTemplateGroup, 'id' | 'createTime' | 'status' | 'templates'> & {
  templates: Array<Omit<StoreTemplate, 'id'>>;
};
export interface StoreRenderBatch {
  id: StoreId;
  status: 'CREATED' | 'RUNNING' | 'STOPPING' | 'STOPPED' | 'COMPLETED' | 'PARTIAL_FAILURE' | 'FAILED' | 'DELETED';
  categoryId: StoreId;
  templateGroupId: StoreId;
  shopId?: StoreId;
  listingTemplateId?: StoreId;
  totalCount: number;
  completedCount: number;
  skippedCount: number;
  failedCount: number;
  createTime?: string;
  archiveUrl?: string;
}
export interface StoreRenderItem {
  id: StoreId;
  batchId: StoreId;
  imageId: StoreId;
  templateId: StoreId;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'SKIPPED' | 'FAILED';
  originalUrl?: string;
  resultUrl?: string;
  errorMessage?: string;
}
export interface StoreRenderGroup { imageId: StoreId; originalName?: string; items: StoreRenderItem[]; }
export interface StoreRenderLog {
  id: StoreId;
  batchId: StoreId;
  imageId: StoreId;
  imageName: string;
  userName: string;
  renderedAt: string;
  renderUseCount: number;
}
export interface StoreListingTemplate {
  id: StoreId;
  shopId: StoreId;
  categoryId: StoreId;
  name: string;
  originalFilename: string;
  configJson?: string;
  versionNo: number;
  templateCode: string;
  createTime?: string;
}
export interface StoreListingExport { id: StoreId; batchId: StoreId; shopId: StoreId; listingTemplateId: StoreId; status: string; errorMessage?: string; filename: string; }
export interface StorePageData<T> { records: T[]; total: number; }

export const listShops = () => request<R<StoreShop[]>>({ url: '/store/shops', method: 'get' });
export const listCalibrations = () => request<R<StoreCalibration[]>>({ url: '/store/calibrations', method: 'get' });
export const saveCalibration = (data: SaveCalibration, id?: StoreId) => request<R<StoreCalibration>>({ url: id == null ? '/store/calibrations' : `/store/calibrations/${id}`, method: id == null ? 'post' : 'patch', data, headers: { repeatSubmit: false } });
export const deleteCalibration = (id: StoreId) => request<R>({ url: `/store/calibrations/${id}`, method: 'delete' });
export const getStoreFile = (key: string, signal?: AbortSignal) => request<Blob>({ url: '/store/files', method: 'get', params: { key }, responseType: 'blob', signal });
export const createShop = (data: CreateStoreShop) => request<R<StoreShop>>({ url: '/store/shops', method: 'post', data, headers: { repeatSubmit: false } });
export const listTemplateGroups = (categoryId?: StoreId, includeDeleted = false) => request<R<StoreTemplateGroup[]>>({ url: '/store/template-groups', method: 'get', params: { categoryId, includeDeleted } });
export const getTemplateGroup = (id: StoreId) => request<R<StoreTemplateGroup>>({ url: `/store/template-groups/${id}`, method: 'get' });
export const createTemplateGroup = (data: SaveTemplateGroup) => request<R<StoreTemplateGroup>>({ url: '/store/template-groups', method: 'post', data });
export const updateTemplateGroup = (id: StoreId, data: SaveTemplateGroup) => request<R<StoreTemplateGroup>>({ url: `/store/template-groups/${id}`, method: 'patch', data });
export const deleteTemplateGroup = (id: StoreId) => request<R>({ url: `/store/template-groups/${id}`, method: 'delete' });
export const setTemplateGroupStatus = (id: StoreId, status: StoreTemplateGroup['status']) => request<R>({ url: `/store/template-groups/${id}/status`, method: 'patch', data: { status } });
export const uploadTemplateImage = (file: File) => {
  const data = new FormData(); data.append('file', file);
  return request<R<{ ossKey: string; imageUrl: string; width: number; height: number }>>({ url: '/store/template-groups/files', method: 'post', data, headers: { repeatSubmit: false } });
};
export const createRenderBatch = (data: {
  categoryId: StoreId; templateGroupId: StoreId; imageIds: StoreId[];
  destinations: Array<{ shopId: StoreId; listingTemplateId: StoreId; titleSelection?: Record<string, number> }>;
  format: 'JPEG' | 'PNG'; quality: number; idempotencyKey: string;
}) => request<R<StoreRenderBatch>>({ url: '/store/render-batches', method: 'post', data, headers: { repeatSubmit: false } });
export const listRenderBatches = (params: { pageNum?: number; pageSize?: number; status?: string; shopId?: StoreId; categoryId?: StoreId }) => request<R<StorePageData<StoreRenderBatch>>>({ url: '/store/render-batches', method: 'get', params });
export const getRenderBatch = (id: StoreId) => request<R<StoreRenderBatch>>({ url: `/store/render-batches/${id}`, method: 'get' });
export const stopRenderBatch = (id: StoreId) => request<R<StoreRenderBatch>>({ url: `/store/render-batches/${id}/stop`, method: 'post', headers: { repeatSubmit: false } });
export const listRenderResults = (params: { batchId: StoreId; pageNum?: number; pageSize?: number }) => request<R<StorePageData<StoreRenderItem>>>({ url: '/store/render-results', method: 'get', params });
export const listRenderGroups = (params: { batchId: StoreId; pageNum?: number; pageSize?: number }) => request<R<StorePageData<StoreRenderGroup>>>({ url: '/store/render-result-groups', method: 'get', params });
export const listRenderLogs = (params: { userName?: string; pageNum?: number; pageSize?: number } = {}) => request<R<StorePageData<StoreRenderLog>>>({ url: '/store/render-logs', method: 'get', params });
export const retryRenderBatch = (id: StoreId) => request<R>({ url: `/store/render-batches/${id}/retry`, method: 'post', headers: { repeatSubmit: false } });
export const getRenderArchive = (id: StoreId, nameMode: 'ORIGINAL_NAME' | 'ENGLISH_KEYWORDS') => request<Blob>({ url: `/store/render-batches/${id}/download`, method: 'get', params: { nameMode }, responseType: 'blob', timeout: 300000 });
export const getRenderArchiveUrl = (id: StoreId, nameMode: 'ORIGINAL_NAME' | 'ENGLISH_KEYWORDS') => request<R<string>>({ url: `/store/render-batches/${id}/download-url`, method: 'get', params: { nameMode }, timeout: 300000 });
export const confirmRenderDownload = (id: StoreId) => request<R>({ url: `/store/render-batches/${id}/download-confirmed`, method: 'post', headers: { repeatSubmit: false } });
export const deleteRenderResults = (id: StoreId) => request<R>({ url: `/store/render-batches/${id}/results`, method: 'delete' });
export const listListingTemplates = (params: { shopId: StoreId; categoryId: StoreId }) => request<R<StoreListingTemplate[]>>({ url: '/store/listing-templates', method: 'get', params });
export const uploadListingTemplate = async (data: { shopId: StoreId; categoryId: StoreId; file: File; configJson: string }) => {
  const body = new FormData(); body.append('file', data.file);
  const uploaded = await request<R<{ ossKey: string; imageUrl: string }>>({ url: '/store/listing-templates/files', method: 'post', data: body, headers: { repeatSubmit: false } });
  return request<R<StoreListingTemplate>>({ url: '/store/listing-templates', method: 'post', data: { shopId: data.shopId, categoryId: data.categoryId, name: data.file.name, ossKey: uploaded.data.ossKey, originalFilename: data.file.name, configJson: data.configJson }, headers: { repeatSubmit: false } });
};
export const deleteListingTemplate = (id: StoreId) => request<R>({ url: `/store/listing-templates/${id}`, method: 'delete' });
export const saveListingProfile = (templateId: StoreId, configJson: string) => request<R<StoreListingTemplate>>({ url: `/store/listing-templates/${templateId}/profile`, method: 'put', data: { configJson } });
export const listListingExports = (params: { batchId?: StoreId; shopId?: StoreId; categoryId?: StoreId } = {}) => request<R<StoreListingExport[]>>({ url: '/store/listing-exports', method: 'get', params });
export const createListingExport = (batchId: StoreId, shopId: StoreId, listingTemplateId: StoreId) => request<R<StoreListingExport>>({ url: '/store/listing-exports', method: 'post', data: { batchId, shopId, listingTemplateId }, headers: { repeatSubmit: false } });
export const retryListingExport = (id: StoreId) => request<R<StoreListingExport>>({ url: `/store/listing-exports/${id}/retry`, method: 'post', headers: { repeatSubmit: false } });
export const downloadListingExport = (id: StoreId) => request<Blob>({ url: `/store/listing-exports/${id}/download`, method: 'get', responseType: 'blob' });
