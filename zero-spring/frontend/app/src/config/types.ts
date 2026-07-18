import { type LucideIcon } from 'lucide-react';

export interface MenuItem {
  title?: string;
  desc?: string;
  img?: string;
  icon?: LucideIcon;
  path?: string;
  rootPath?: string;
  childrenIndex?: number;
  heading?: string;
  children?: MenuConfig;
  disabled?: boolean;
  collapse?: boolean;
  collapseTitle?: string;
  expandTitle?: string;
  badge?: string;
  separator?: boolean;
  /**
   * Permission key required to see this item (RBAC).
   * When omitted the item is visible to any authenticated user.
   */
  permission?: string;
  /**
   * Any-of permission keys: the item is visible when the user holds AT LEAST
   * ONE of these (mirrors `RequireAuth`'s `anyPermission`). Combines with
   * `permission` as an independent gate when both are present.
   */
  anyPermission?: string[];
}

export type MenuConfig = MenuItem[];
