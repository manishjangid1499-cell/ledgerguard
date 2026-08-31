import { tokenStore } from '../../auth/api/tokenStore';
import { AuthResponse } from '../../auth/types/auth.types';
import { ApiError } from '../types/api.types';
import { parseProblemDetail } from './problemDetail';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');

const AUTH_EXCLUSIONS = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
];

interface RequestOptions extends RequestInit {
  _retry?: boolean;
}

let activeRefreshPromise: Promise<AuthResponse | null> | null = null;

export async function executeSingleFlightRefresh(): Promise<AuthResponse | null> {
  if (activeRefreshPromise) {
    return activeRefreshPromise;
  }

  activeRefreshPromise = (async () => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        credentials: 'include',
      });

      if (!response.ok) {
        tokenStore.clearAccessToken();
        return null;
      }

      const data = (await response.json()) as AuthResponse;
      if (data && typeof data.accessToken === 'string') {
        tokenStore.setAccessToken(data.accessToken);
        return data;
      }

      tokenStore.clearAccessToken();
      return null;
    } catch {
      tokenStore.clearAccessToken();
      return null;
    } finally {
      activeRefreshPromise = null;
    }
  })();

  return activeRefreshPromise;
}

export async function apiClient<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const url = endpoint.startsWith('http') ? endpoint : `${API_BASE_URL}${endpoint.startsWith('/') ? '' : '/'}${endpoint}`;
  const isAuthExclusion = AUTH_EXCLUSIONS.some((path) => endpoint.includes(path));

  const headers = new Headers(options.headers || {});
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  const token = tokenStore.getAccessToken();
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  if (options.body && typeof options.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  try {
    const response = await fetch(url, {
      ...options,
      headers,
      credentials: 'include',
    });

    // Handle 401 Unauthorized for protected endpoints with single-flight refresh retry
    if (response.status === 401 && !isAuthExclusion && !options._retry) {
      const authResult = await executeSingleFlightRefresh();
      if (authResult?.accessToken) {
        // Retry the original request once with the new access token
        return apiClient<T>(endpoint, {
          ...options,
          _retry: true,
        });
      }
    }

    if (!response.ok) {
      let rawData: unknown = null;
      try {
        rawData = await response.json();
      } catch {
        // Body is not JSON or empty
      }
      const problem = parseProblemDetail(response.status, rawData);
      throw new ApiError(problem);
    }

    if (response.status === 204) {
      return undefined as unknown as T;
    }

    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }

    // Network / fetch failures
    throw new ApiError({
      status: 0,
      title: 'Network Error',
      detail: 'Unable to connect to LedgerGuard server. Please check your network connection.',
    });
  }
}
