import type { RoleVO } from '@/api/system/role/types';
import type { BaseEntity, PageQuery } from '@/api/types';

export interface UserInfo {
  user: {
    userId: string | number;
    userName: string;
    nickName?: string;
    avatar?: string | number;
    avatarUrl?: string;
    riskScanEnabled?: boolean;
    relayAnalysisEnabled?: boolean;
    renderAllRisksEnabled?: boolean;
    listingAllRisksEnabled?: boolean;
  };
  roles: string[];
  permissions: string[];
}

export interface UserForm {
  userId?: string | number;
  deptId?: string | number;
  userName?: string;
  nickName?: string;
  password?: string;
  phoneNumber?: string;
  email?: string;
  gender?: string;
  status?: string;
  remark?: string;
  avatar?: string | number;
  roleIds?: Array<string | number>;
  riskScanEnabled?: boolean;
  relayAnalysisEnabled?: boolean;
  renderAllRisksEnabled?: boolean;
  listingAllRisksEnabled?: boolean;
}

export interface UserProfileForm {
  nickName?: string;
  phoneNumber?: string;
  email?: string;
  gender?: string;
  avatar?: string | number;
}

export interface UserInfoVO {
  user?: UserVO;
  roles: RoleVO[];
  roleIds: Array<string | number>;
  roleGroup?: string;
}

export interface UserQuery extends PageQuery {
  userName?: string;
  nickName?: string;
  phoneNumber?: string;
  status?: string;
  userIds?: string | number;
}

export interface UserVO extends BaseEntity {
  userId?: string | number;
  deptId?: string | number;
  deptName?: string;
  tenantId?: string;
  userName?: string;
  nickName?: string;
  userType?: string;
  email?: string;
  phoneNumber?: string;
  gender?: string;
  avatar?: string | number;
  avatarUrl?: string;
  status?: string;
  roles?: RoleVO[];
  admin?: boolean;
  riskScanEnabled?: boolean;
  relayAnalysisEnabled?: boolean;
  renderAllRisksEnabled?: boolean;
  listingAllRisksEnabled?: boolean;
  loginIp?: string;
  loginDate?: string;
}
