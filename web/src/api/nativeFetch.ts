/**
 * The fetch implementation captured at module load, BEFORE client.ts installs
 * its window.fetch monkeypatch. fetchWithAuth and refreshSingleton call this so
 * they never recurse into their own wrapper.
 *
 * Import order makes this correct: client.ts imports this module (and
 * refreshSingleton, which imports this module) at the top, so nativeFetch is
 * captured before client.ts runs the patch in its body. In tests, MSW patches
 * global fetch in setup (which runs before any test module is imported), so the
 * captured reference is MSW's interceptor and requests still hit mock handlers.
 */
export const nativeFetch: typeof fetch = globalThis.fetch.bind(globalThis);
