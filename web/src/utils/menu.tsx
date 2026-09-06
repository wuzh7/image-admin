import {
  AppstoreOutlined,
  AuditOutlined,
  CloudUploadOutlined,
  PartitionOutlined,
  PictureOutlined,
  SettingOutlined,
  TeamOutlined,
  UnorderedListOutlined,
  UserOutlined
} from '@ant-design/icons';
import { Icon } from '@iconify/react';
import type { BackendRoute, RuntimeMenuItem } from '@/api/types';
import { isHttp, normalizePath } from '@/utils/wuzh';

const iconMap: Record<string, React.ReactNode> = {
  user: <UserOutlined />,
  peoples: <TeamOutlined />,
  system: <SettingOutlined />,
  'tree-table': <PartitionOutlined />,
  upload: <CloudUploadOutlined />,
  'unordered-list': <UnorderedListOutlined />,
  audit: <AuditOutlined />,
  picture: <PictureOutlined />
};

export function routeIcon(icon?: string | null) {
  const iconValue = typeof icon === 'string' ? icon.trim() : '';
  if (!iconValue) return <AppstoreOutlined />;
  const normalizedIcon = iconValue.replace(/^i-/, '');
  if (normalizedIcon.includes(':')) return <Icon icon={normalizedIcon} />;
  return iconMap[iconValue] || iconMap[normalizedIcon] || iconMap[normalizedIcon.toLowerCase()] || <AppstoreOutlined />;
}

function joinPath(parentPath: string, childPath: string) {
  if (isHttp(childPath)) return childPath;
  if (childPath.startsWith('/')) return childPath;
  if (!parentPath || parentPath === '/') return normalizePath(childPath);
  return normalizePath(`${parentPath}/${childPath}`);
}

export function backendRoutesToMenu(routes: BackendRoute[] = [], parentPath = ''): RuntimeMenuItem[] {
  return routes
    .filter(route => !route.hidden)
    .map(route => {
      const path = joinPath(parentPath, route.path);
      const visibleChildren = (route.children || []).filter(child => !child.hidden);
      if (visibleChildren.length === 1 && !route.alwaysShow && !visibleChildren[0].children?.length) {
        const child = visibleChildren[0];
        const childPath = joinPath(path, child.path);
        return {
          path: childPath,
          name: child.meta?.title || child.name || child.path,
          icon: routeIcon(child.meta?.icon || route.meta?.icon),
          backendRoute: child
        };
      }

      const children = visibleChildren.length ? backendRoutesToMenu(visibleChildren, path) : undefined;
      return {
        path,
        name: route.meta?.title || route.name || route.path,
        icon: routeIcon(route.meta?.icon),
        backendRoute: route,
        routes: children
      };
    });
}

export function appendRouteQuery(path: string, routeQuery?: string) {
  if (!routeQuery || isHttp(path)) return path;
  try {
    const params = new URLSearchParams(JSON.parse(routeQuery) as Record<string, string>);
    const search = params.toString();
    return search ? `${path}?${search}` : path;
  } catch {
    return path;
  }
}

export function flattenBackendRoutes(
  routes: BackendRoute[],
  parentPath = ''
): Array<BackendRoute & { fullPath: string }> {
  const result: Array<BackendRoute & { fullPath: string }> = [];
  for (const route of routes) {
    const fullPath = joinPath(parentPath, route.path);
    result.push({ ...route, fullPath });
    if (route.children?.length) {
      result.push(...flattenBackendRoutes(route.children, fullPath));
    }
  }
  return result;
}
