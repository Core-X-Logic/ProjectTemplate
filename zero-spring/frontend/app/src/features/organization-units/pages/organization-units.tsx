import { OuTreePage } from './ou-tree';

/**
 * Route element registered in `routing/routes.tsx` (kept as the stable export
 * so shared files stay untouched). The actual screen lives in `ou-tree.tsx`.
 */
export function OrganizationUnitsPage() {
  return <OuTreePage />;
}
