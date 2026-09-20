import { tokenStore } from './tokenStore';

/**
 * Authentication session lifecycle and generation management.
 * Protects against race conditions between:
 * - Logout
 * - In-flight refresh / restore-session requests
 * - 401 auto-refresh retries
 *
 * Any in-flight operation captures authSession.getEpoch() at start.
 * If logout occurs before the operation completes, the epoch increments and
 * loggingOut is set, causing the stale result to be rejected and discarded.
 */
let currentEpoch = 0;
let loggingOut = false;

export const authSession = {
  getEpoch(): number {
    return currentEpoch;
  },

  isValidEpoch(epoch: number): boolean {
    return !loggingOut && epoch === currentEpoch;
  },

  isLoggingOut(): boolean {
    return loggingOut;
  },

  startNewSession(): number {
    loggingOut = false;
    currentEpoch += 1;
    return currentEpoch;
  },

  invalidateSession(): number {
    loggingOut = true;
    currentEpoch += 1;
    tokenStore.clearAccessToken();
    return currentEpoch;
  },

  resetForTest(): void {
    currentEpoch = 0;
    loggingOut = false;
  },
};
