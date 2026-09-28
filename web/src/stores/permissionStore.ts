import { create } from 'zustand';
import type { BackendRoute, RuntimeMenuItem } from '@/api/types';
import { getInfo } from '@/api/login';
import { getRouters } from '@/api/menu';
import { backendRoutesToMenu } from '@/utils/menu';
import { hasRole } from '@/utils/permission';
import { useUserStore } from './userStore';

function hideAdminRoutes(routes: BackendRoute[]): BackendRoute[] {
  return routes.flatMap(route => {
    if (route.component?.includes('tro/index') || route.component?.includes('order/detail/index')) return [];
    const children = route.children ? hideAdminRoutes(route.children) : undefined;
    return route.children?.length && !children?.length ? [] : [{ ...route, children }];
  });
}

interface PermissionState {
  backendRoutes: BackendRoute[];
  menuData: RuntimeMenuItem[];
  reloadMenus: () => Promise<void>;
  clearMenus: () => void;
}

export const usePermissionStore = create<PermissionState>(set => ({
  backendRoutes: [],
  menuData: [],
  reloadMenus: async () => {
    const [infoRes, routerRes] = await Promise.all([getInfo(), getRouters()]);
    const backendRoutes = hasRole(infoRes.data, ['superadmin'])
      ? routerRes.data || [] : hideAdminRoutes(routerRes.data || []);
    useUserStore.getState().setUserInfo(infoRes.data);
    set({
      backendRoutes,
      menuData: backendRoutesToMenu(backendRoutes)
    });
  },
  clearMenus: () => {
    set({ backendRoutes: [], menuData: [] });
  }
}));
