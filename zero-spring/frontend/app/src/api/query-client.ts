import { QueryClient } from '@tanstack/react-query';

/**
 * Shared TanStack Query client (FRONTEND-ARCHITECTURE.md §7).
 *
 * - `staleTime` 30s: server state is considered fresh for half a minute,
 *   avoiding refetch storms on navigation.
 * - `retry` 1: one automatic retry on transient failures.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: 0,
    },
  },
});
