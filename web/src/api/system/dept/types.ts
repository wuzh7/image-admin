import type { BaseEntity } from '@/api/types';

export interface DeptTreeOption {
  id: string | number;
  label: string;
  disabled?: boolean;
  children?: DeptTreeOption[];
}

export interface DeptVO extends BaseEntity {
  deptId: string | number;
  parentId: string | number;
  parentName?: string;
  deptName: string;
  status: string;
  roleIds?: Array<string | number>;
  children?: DeptVO[];
}

export interface DeptForm {
  deptId?: string | number;
  parentId?: string | number;
  parentName?: string;
  deptName?: string;
  status?: string;
  roleIds?: Array<string | number>;
}
