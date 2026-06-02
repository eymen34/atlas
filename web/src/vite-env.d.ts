/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Client-side access-token lifetime fallback (seconds) used when the refresh
   *  response omits an absolute expiry. Defaults to 900. Mirrors JWT_ACCESS_TTL_SECONDS. */
  readonly VITE_ACCESS_TTL_SECONDS?: string;
}
