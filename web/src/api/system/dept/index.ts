import request from '@/api/request';
import type { R } from '@/api/types';
import type { RoleVO } from '@/api/system/role/types';
import type { DeptForm, DeptTreeOption, DeptVO } from './types';
import type { MenuTreeOption } from '@/api/system/menu/types';

export function listDept(params?: { deptName?: string; status?: string }) {
  return request<R<DeptVO[]>>({ url: '/system/dept/list', method: 'get', params });
}

export function deptTree() {
  return request<R<DeptTreeOption[]>>({ url: '/system/dept/treeselect', method: 'get' });
}

export function getDept(deptId: string | number) {
  return request<R<DeptVO>>({ url: `/system/dept/${deptId}`, method: 'get' });
}

export function getDeptRoles(deptId: string | number) {
  return request<R<RoleVO[]>>({ url: `/system/dept/${deptId}/roles`, method: 'get' });
}

export function deptPermissionTree() {
  return request<R<MenuTreeOption[]>>({ url: '/system/dept/permissionTree', method: 'get' });
}

export function getDeptPermissions(deptId: string | number) {
  return request<R<Array<string | number>>>({ url: `/system/dept/${deptId}/permissions`, method: 'get' });
}

export function updateDeptPermissions(deptId: string | number, menuIds: Array<string | number>) {
  return request<R>({ url: `/system/dept/${deptId}/permissions`, method: 'put', data: menuIds });
}

export function addDept(data: DeptForm) {
  return request<R>({ url: '/system/dept', method: 'post', data });
}

export function updateDept(data: DeptForm) {
  return request<R>({ url: '/system/dept', method: 'put', data });
}

export function deleteDept(deptId: string | number) {
  return request<R>({ url: `/system/dept/${deptId}`, method: 'delete' });
}
