import { ConfigService } from './generated';

/**
 * T-023 public-config wrapper. The unauthenticated GET /api/config/public exposes
 * non-sensitive feature flags the SPA needs before login (e.g. whether to render
 * the watch toggle). Components go through this wrapper, never the generated
 * service directly (frontend_api_wrapper).
 */
export const configKeys = {
  all: ['config'] as const,
  public: ['config', 'public'] as const,
};

export type PublicConfig = { features: { watchers: boolean } };

export async function getPublicConfig(): Promise<PublicConfig> {
  const res = await ConfigService.getPublicConfig();
  // The generated model types every field optional; validate the shape we rely on.
  if (!res?.features || typeof res.features.watchers !== 'boolean') {
    throw new Error('Malformed PublicConfig response');
  }
  return { features: { watchers: res.features.watchers } };
}
