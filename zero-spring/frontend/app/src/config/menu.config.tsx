import { useMemo } from 'react';
import {
  Bell,
  Building,
  Building2,
  CreditCard,
  LayoutGrid,
  Package,
  ScrollText,
  Settings,
  ShieldCheck,
  Store,
  Users,
} from 'lucide-react';
import { useAuth } from '@/providers/auth-provider';
import { MenuConfig, MenuItem } from './types';

/**
 * Product sidebar menu (FRONTEND-ARCHITECTURE.md §5).
 *
 * `title` holds an i18n message id (resolved at render). Each protected item
 * carries a `permission`; items without one are visible to any authenticated
 * user. The backend enforces the same permissions (double lock).
 */
export const MENU_SIDEBAR: MenuConfig = [
  {
    title: 'nav.dashboard',
    icon: LayoutGrid,
    path: '/',
  },
  {
    title: 'nav.users',
    icon: Users,
    path: '/users',
    permission: 'users.read',
  },
  {
    title: 'nav.roles',
    icon: ShieldCheck,
    path: '/roles',
    permission: 'roles.read',
  },
  {
    title: 'nav.organizationUnits',
    icon: Building2,
    path: '/organization-units',
    permission: 'organizationunits.manage',
  },
  {
    // Host-only: `tenants.manage` is declared `Side.HOST`, so a tenant-side
    // role can never hold it and the entry never renders for tenant operators.
    title: 'nav.tenants',
    icon: Building,
    path: '/tenants',
    permission: 'tenants.manage',
  },
  {
    title: 'nav.notifications',
    icon: Bell,
    path: '/notifications',
  },
  {
    title: 'nav.audit',
    icon: ScrollText,
    path: '/audit',
    permission: 'auditlogs.read',
  },
  {
    // SaaS group (F5 slice A). It has no path of its own, so
    // `filterMenuByPermission` drops the whole group once a user can see
    // neither child — a tenant operator never sees a "Saas" heading.
    title: 'nav.saas',
    icon: Store,
    children: [
      {
        title: 'nav.editions',
        icon: Package,
        path: '/editions',
        permission: 'editions.read',
      },
      {
        title: 'nav.subscriptions',
        icon: CreditCard,
        path: '/subscriptions',
        permission: 'subscriptions.read',
      },
    ],
  },
  {
    title: 'nav.settings',
    icon: Settings,
    path: '/settings',
    // Visible to tenant OR host operators; the route + page gate the scopes.
    anyPermission: ['settings.tenant.manage', 'settings.host.manage'],
  },
];

/**
 * Filter a menu tree by the permissions the user holds. Parent items are kept
 * only when they still have at least one visible child (or a path of their own).
 */
export function filterMenuByPermission(
  items: MenuConfig,
  can: (permission: string) => boolean,
): MenuConfig {
  return items.reduce<MenuConfig>((acc, item) => {
    if (item.children && item.children.length > 0) {
      const children = filterMenuByPermission(item.children, can);
      if (children.length === 0 && !item.path) {
        return acc;
      }
      acc.push({ ...item, children });
      return acc;
    }

    if (item.permission && !can(item.permission)) {
      return acc;
    }

    if (
      item.anyPermission &&
      item.anyPermission.length > 0 &&
      !item.anyPermission.some(can)
    ) {
      return acc;
    }

    acc.push(item);
    return acc;
  }, []);
}

/** Reactive, permission-filtered sidebar menu for the current user. */
export function useSidebarMenu(): MenuConfig {
  const { permissions } = useAuth();
  return useMemo(
    () =>
      filterMenuByPermission(MENU_SIDEBAR, (permission) =>
        permissions.includes(permission),
      ),
    [permissions],
  );
}

export type { MenuItem };
