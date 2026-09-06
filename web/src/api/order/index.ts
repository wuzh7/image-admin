import type { PageResult, R } from '@/api/types';
import request from '@/api/request';
import type { OrderDetailForm, OrderDetailQuery, OrderDetailVO } from './types';

export function listOrderDetails(query: OrderDetailQuery) {
  return request<R<PageResult<OrderDetailVO>>>({
    url: '/order/details/list',
    method: 'get',
    params: query
  });
}

export function getOrderDetail(id: string | number) {
  return request<R<OrderDetailVO>>({
    url: `/order/details/${id}`,
    method: 'get'
  });
}

export function addOrderDetail(data: OrderDetailForm) {
  return request<R>({
    url: '/order/details',
    method: 'post',
    data
  });
}

export function updateOrderDetail(data: OrderDetailForm) {
  return request<R>({
    url: '/order/details',
    method: 'put',
    data
  });
}

export function deleteOrderDetails(ids: Array<string | number> | string | number) {
  return request<R>({
    url: `/order/details/${ids}`,
    method: 'delete'
  });
}
