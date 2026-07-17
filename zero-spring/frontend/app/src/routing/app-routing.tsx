import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { useLoadingBar } from 'react-top-loading-bar';
import { AppRoutes } from '@/routing/routes';

/**
 * Wraps the route tree with a top loading bar that animates on navigation
 * (vendor layout-1 adaptation; requires an enclosing `LoadingBarContainer`).
 */
export function AppRouting() {
  const { start, complete } = useLoadingBar({
    color: 'var(--primary)',
    height: 2,
    shadow: false,
    waitingTime: 400,
    transitionTime: 200,
  });

  const location = useLocation();
  const firstRender = useRef(true);

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    start('static');
    const timer = setTimeout(() => complete(), 100);
    return () => clearTimeout(timer);
  }, [location.pathname, start, complete]);

  return <AppRoutes />;
}
