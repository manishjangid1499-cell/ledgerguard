/**
 * In-memory token storage for LedgerGuard.
 * Access tokens reside strictly in JavaScript application memory.
 * Never written to localStorage, sessionStorage, IndexedDB, or client cookies.
 */
let inMemoryAccessToken: string | null = null;

export const tokenStore = {
  getAccessToken(): string | null {
    return inMemoryAccessToken;
  },

  setAccessToken(token: string | null): void {
    inMemoryAccessToken = token;
  },

  clearAccessToken(): void {
    inMemoryAccessToken = null;
  },
};
