import { useQuery } from '@tanstack/react-query';
import { configKeys, getPublicConfig } from '@/api/config';

/**
 * Reads the public feature-flag config (T-023). Cached aggressively — the flags
 * change rarely — so flag-gated UI can decide render/no-render without refetching
 * per mount.
 */
export function usePublicConfig() {
  const { data: config, isLoading } = useQuery({
    queryKey: configKeys.public,
    queryFn: getPublicConfig,
    staleTime: 5 * 60_000,
    gcTime: 30 * 60_000,
    retry: 1,
  });
  return { config, isLoading };
}
