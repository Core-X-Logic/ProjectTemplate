import { Helmet } from 'react-helmet-async';
import { LayoutProvider } from './components/context';
import { Main } from './components/main';

/**
 * Product admin shell — vendor Metronic layout-1 adapted to the product menu
 * (FRONTEND-ARCHITECTURE.md §2). Renders the routed page via `<Outlet />`.
 */
export function AdminLayout() {
  return (
    <>
      <Helmet>
        <title>Zero Platform</title>
      </Helmet>

      <LayoutProvider>
        <Main />
      </LayoutProvider>
    </>
  );
}
