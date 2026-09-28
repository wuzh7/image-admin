import { useLocation } from '@umijs/max';
import { useMemo } from 'react';
import ImageCategoryPage from '@/pages/image/category';
import ImageDedupPage from '@/pages/image/dedup';
import ImageInfringementPage from '@/pages/image/infringement';
import CalibrationPage from '@/pages/store/calibration';
import TemplateGroupPage from '@/pages/store/template-group';
import RenderResultPage from '@/pages/store/render-result';
import ListingPage from '@/pages/store/listing';
import ListingConfigPage from '@/pages/store/listing-config';
import StoreOperationsPage from '@/pages/store/operations';
import StoreReferencePage from '@/pages/store/reference';
import OrderDetailPage from '@/pages/order/detail';
import TroPage from '@/pages/tro';
import SystemMenuPage from '@/pages/system/menu';
import SystemDeptPage from '@/pages/system/dept';
import SystemRolePage from '@/pages/system/role';
import SystemUserPage from '@/pages/system/user';
import UserAuthRolePage from '@/pages/system/user/authRole';
import SystemStoreProbePage from '@/pages/system/store-probe';
import { useUserStore } from '@/stores/userStore';
import { hasRole } from '@/utils/permission';
import { usePermissionStore } from '@/stores/permissionStore';
import { flattenBackendRoutes } from '@/utils/menu';

const migratedPages: Record<string, React.ComponentType> = {
  'image/category/index': ImageCategoryPage,
  'image/dedup/index': ImageDedupPage,
  'image/infringement/index': ImageInfringementPage,
  'store/calibration/index': CalibrationPage,
  'store/template-group/index': TemplateGroupPage,
  'store/render-result/index': RenderResultPage,
  'store/listing/index': ListingPage,
  'store/listing-config/index': ListingConfigPage,
  'store/operations/index': StoreOperationsPage,
  'store/reference/index': StoreReferencePage,
  'order/detail/index': OrderDetailPage,
  'tro/index': TroPage,
  'system/menu/index': SystemMenuPage,
  'system/dept/index': SystemDeptPage,
  'system/role/index': SystemRolePage,
  'system/user/authRole': UserAuthRolePage,
  'system/user/index': SystemUserPage,
  'system/store-probe/index': SystemStoreProbePage
};

const migratedPathPrefixes: Array<[string, React.ComponentType]> = [
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
  if ((componentKey === 'image/category/index' || componentKey === 'tro/index' || componentKey === 'order/detail/index')
    && !hasRole(userInfo, ['superadmin'])) return null;
  if (MigratedPage) {
    return <MigratedPage />;
  }

  return null;
}
