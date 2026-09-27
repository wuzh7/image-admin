import request from '@/api/request';
import type { R } from '@/api/types';

export interface StoreReference { id: string | number; name: string; status: '0' | '1'; }
export interface StoreOperator { id: string | number; name: string; account: string; }
export interface SaveStoreReference { name: string; status: '0' | '1'; }
export const listStoreReferences = (kind: 'PLATFORM' | 'SITE', activeOnly = false) => request<R<StoreReference[]>>({ url: `/store/references/${kind}${activeOnly ? '/options' : ''}`, method: 'get' });
export const saveStoreReference = (kind: 'PLATFORM' | 'SITE', data: SaveStoreReference, id?: string | number) => request<R<StoreReference>>({ url: `/store/references/${kind}${id == null ? '' : `/${id}`}`, method: id == null ? 'post' : 'put', data });
export const deleteStoreReference = (kind: 'PLATFORM' | 'SITE', id: string | number) => request<R>({ url: `/store/references/${kind}/${id}`, method: 'delete' });
export const listStoreOperators = () => request<R<StoreOperator[]>>({ url: '/store/operators', method: 'get' });
