import type { MenuTreeOption } from '@/api/system/menu/types';
import type { BaseEntity, PageQuery } from '@/api/types';

export interface RoleForm {
  roleId?: string | number;
  roleName?: string;
  roleKey?: string;
  roleSort?: number;
  status?: string;
  menuCheckStrictly?: boolean;
  remark?: string;
  dataScope?: string;
  menuIds?: Array<string | number>;
}

export interface RoleMenuTree {
  menus: MenuTreeOption[];
  checkedKeys: Array<string | number>;
}

export interface RoleQuery extends PageQuery {
  roleName?: string;
  roleKey?: string;
  status?: string;
}

export interface RoleVO extends BaseEntity {
  roleId: string | number;
  roleName: string;
  roleKey: string;
  roleSort: number;
  dataScope: string;
  menuCheckStrictly?: boolean;
  status: string;
  flag: boolean;
  admin: boolean;
  menuIds?: Array<string | number>;
}
