/**
 * Application environment configuration and feature flags.
 */

/**
 * Determines if Failure Lab is enabled.
 * Excluded in production by default; enabled strictly when explicitly built/configured
 * with VITE_ENABLE_FAILURE_LAB === 'true'.
 */
export const isFailureLabEnabled = (): boolean => {
  return import.meta.env.VITE_ENABLE_FAILURE_LAB === 'true';
};
