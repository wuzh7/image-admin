import request from '@/api/request';
import type { PageResult, R } from '@/api/types';

export interface OperationLog {
  operId: string | number;
  title: string;
  businessType: number;
  requestMethod?: string;
  operName?: string;
  operUrl?: string;
  operParam?: string;
  jsonResult?: string;
  status: number;
  errorMsg?: string;
  operTime?: string;
  costTime?: number;
}

export function listRecentOperationLogs(userName?: string) {
  return request<R<PageResult<OperationLog>>>({ url: '/monitor/operlog/recent', method: 'get', params: { userName } });
}
