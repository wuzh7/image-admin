import type { PageResult, R } from '@/api/types';
import request from '@/api/request';
import type { RoleForm, RoleQuery, RoleVO } from './types';

export function listRole(query: RoleQuery) {
  return request<R<PageResult<RoleVO>>>({
    url: '/system/role/list',
    method: 'get',
    params: query
  });
}

export function optionSelect(roleIds: Array<number | string>) {
  return request<R<RoleVO[]>>({
    url: `/system/role/optionselect?roleIds=${roleIds}`,
    method: 'get'
  });
}

export function getRole(roleId: string | number) {
  return request<R<RoleVO>>({
    url: `/system/role/${roleId}`,
    method: 'get'
  });
}

export function addRole(data: RoleForm) {
  return request<R>({
    url: '/system/role',
    method: 'post',
    data
  });
}

export function updateRole(data: RoleForm) {
  return request<R>({
    url: '/system/role',
    method: 'put',
    data
  });
}

export function updateRolePermission(data: RoleForm) {
  return request<R>({
    url: '/system/role/permission',
    method: 'put',
    data
  });
}

export function changeRoleStatus(roleId: string | number, status: string) {
  return request<R>({
    url: '/system/role/changeStatus',
    method: 'put',
    data: {
      roleId,
      status
    }
  });
}

export function delRole(roleId: Array<string | number> | string | number) {
  return request<R>({
    url: `/system/role/${roleId}`,
    method: 'delete'
  });
}

