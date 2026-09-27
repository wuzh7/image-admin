import request from '@/api/request';
import type { PageQuery, PageResult, R } from '@/api/types';

export interface StoreProbe {
  id: string | number;
  storeId: string;
  storeName?: string;
  remarks?: string;
  createTime?: string;
}

export type StoreProbeForm = Omit<StoreProbe, 'createTime'>;

export function listStoreProbes(params: PageQuery & { storeId?: string; storeName?: string }) {
  return request<R<PageResult<StoreProbe>>>({ url: '/system/store-probe/list', method: 'get', params });
}

export function getStoreProbe(id: string | number) {
  return request<R<StoreProbe>>({ url: `/system/store-probe/${id}`, method: 'get' });
}

export function addStoreProbe(data: Omit<StoreProbeForm, 'id'>) {
  return request<R<StoreProbe>>({ url: '/system/store-probe', method: 'post', data });
}

export function updateStoreProbe(data: StoreProbeForm) {
  return request<R<StoreProbe>>({ url: '/system/store-probe', method: 'put', data });
}

export function deleteStoreProbe(id: string | number) {
  return request<R>({ url: `/system/store-probe/${id}`, method: 'delete' });
}
