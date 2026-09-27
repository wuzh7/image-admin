import request from '@/api/request';
import type { PageQuery, PageResult, R } from '@/api/types';

export interface TroCase {
  id?: string;
  caseNoFormat: string;
  caseName?: string;
  court?: string;
  judge?: string;
  description?: string;
  natureOfSuit?: string;
  cause?: string;
  dateFiled: string;
  plaintiff?: string;
  plaintiffCn?: string;
  plaintiffNames?: string[];
  lawyer?: string;
  lawFirms?: string;
  createTime?: string;
  updateTime?: string;
}

export interface TroQuery extends PageQuery {
  caseNoFormat?: string;
  caseName?: string;
  plaintiff?: string;
}

export function listTroCases(query: TroQuery) {
  return request<R<PageResult<TroCase>>>({ url: '/tro/cases/list', method: 'get', params: query });
}

export function importTroCases(file: File) {
  const data = new FormData();
  data.append('file', file);
  return request<R<number>>({ url: '/tro/cases/import', method: 'post', data, headers: { repeatSubmit: false } });
}
