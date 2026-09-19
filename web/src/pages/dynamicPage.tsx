import { useLocation } from '@umijs/max';
import { useMemo } from 'react';
import ImageCategoryPage from '@/pages/image/category';
import ImageDedupPage from '@/pages/image/dedup';
import ImageInfringementPage from '@/pages/image/infringement';
import OrderDetailPage from '@/pages/order/detail';
import SystemMenuPage from '@/pages/system/menu';
import SystemDeptPage from '@/pages/system/dept';
import SystemRolePage from '@/pages/system/role';
import RoleAuthUserPage from '@/pages/system/role/authUser';
import SystemUserPage from '@/pages/system/user';
import UserAuthRolePage from '@/pages/system/user/authRole';
import { useUserStore } from '@/stores/userStore';
import { hasRole } from '@/utils/permission';
import { usePermissionStore } from '@/stores/permissionStore';
import { flattenBackendRoutes } from '@/utils/menu';

const migratedPages: Record<string, React.ComponentType> = {
  'image/category/index': ImageCategoryPage,
  'image/dedup/index': ImageDedupPage,
  'image/infringement/index': ImageInfringementPage,
  'order/detail/index': OrderDetailPage,
  'system/menu/index': SystemMenuPage,
  'system/dept/index': SystemDeptPage,
  'system/role/authUser': RoleAuthUserPage,
  'system/role/index': SystemRolePage,
  'system/user/authRole': UserAuthRolePage,
  'system/user/index': SystemUserPage
};

const migratedPathPrefixes: Array<[string, React.ComponentType]> = [
  ['/system/role-auth/user/', RoleAuthUserPage],
  ['/system/user-auth/role/', UserAuthRolePage]
];

function normalizeComponentKey(component?: string) {
  return (component || '')
    .replace(/^@\/views\//, '')
    .replace(/^\.?\//, '')
    .replace(/\.vue$/, '')
    .replace(/\/$/, '');
}

export default function DynamicPage() {
  const location = useLocation();
  const backendRoutes = usePermissionStore(state => state.backendRoutes);
  const currentRoute = useMemo(
    () => flattenBackendRoutes(backendRoutes).find(route => route.fullPath === location.pathname),
    [backendRoutes, location.pathname]
  );
  const componentKey = normalizeComponentKey(currentRoute?.component);
  const MigratedPage =
    migratedPages[componentKey] || migratedPathPrefixes.find(([prefix]) => location.pathname.startsWith(prefix))?.[1];
  const userInfo = useUserStore(state => state.userInfo);
  if ((location.pathname.startsWith('/system/') || componentKey === 'image/category/index') && !hasRole(userInfo, ['superadmin'])) return null;
  if (MigratedPage) {
    return <MigratedPage />;
  }

  return null;
}
