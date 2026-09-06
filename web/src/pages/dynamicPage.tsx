import { useLocation } from '@umijs/max';
import { useMemo } from 'react';
import ImageCategoryPage from '@/pages/image/category';
import ImageDedupPage from '@/pages/image/dedup';
import OrderDetailPage from '@/pages/order/detail';
import SystemMenuPage from '@/pages/system/menu';
import SystemRolePage from '@/pages/system/role';
import RoleAuthUserPage from '@/pages/system/role/authUser';
import SystemUserPage from '@/pages/system/user';
import UserAuthRolePage from '@/pages/system/user/authRole';
import { usePermissionStore } from '@/stores/permissionStore';
import { flattenBackendRoutes } from '@/utils/menu';

const migratedPages: Record<string, React.ComponentType> = {
  'image/category/index': ImageCategoryPage,
  'image/dedup/index': ImageDedupPage,
  'order/detail/index': OrderDetailPage,
  'system/menu/index': SystemMenuPage,
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
  if (MigratedPage) {
    return <MigratedPage />;
  }

  return null;
}
