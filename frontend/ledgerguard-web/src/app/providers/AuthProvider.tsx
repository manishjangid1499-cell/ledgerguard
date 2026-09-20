import React, { useEffect, useState, useCallback } from 'react';
import { authApi } from '../../auth/api/authApi';
import { authSession } from '../../auth/api/authSession';
import { tokenStore } from '../../auth/api/tokenStore';
import { LoginCredentials, RegisterPayload } from '../../auth/types/auth.types';
import { UserSummary } from '../../shared/types/user.types';
import { AuthContext, AuthStatus, AuthContextType } from './authContext';
import { queryClient } from './queryClient';

interface AuthProviderProps {
  children: React.ReactNode;
}

let activeLogoutPromise: Promise<void> | null = null;

export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<UserSummary | null>(null);

  // Restore session on initial application load via HttpOnly refresh cookie
  useEffect(() => {
    let isMounted = true;
    const epochAtStart = authSession.getEpoch();

    async function restoreSession() {
      try {
        const response = await authApi.refresh();
        if (!authSession.isValidEpoch(epochAtStart) || !isMounted) {
          return;
        }

        if (response?.accessToken && response?.user) {
          tokenStore.setAccessToken(response.accessToken);
          setUser(response.user);
          setStatus('authenticated');
          queryClient.setQueryData(['currentUser'], response.user);
        } else {
          tokenStore.clearAccessToken();
          setUser(null);
          setStatus('unauthenticated');
        }
      } catch {
        if (authSession.isValidEpoch(epochAtStart) && isMounted) {
          tokenStore.clearAccessToken();
          setUser(null);
          setStatus('unauthenticated');
        }
      }
    }

    restoreSession();

    return () => {
      isMounted = false;
    };
  }, []);

  const login = useCallback(async (credentials: LoginCredentials) => {
    const epochAtStart = authSession.startNewSession();
    try {
      const response = await authApi.login(credentials);
      if (!authSession.isValidEpoch(epochAtStart)) {
        return;
      }
      tokenStore.setAccessToken(response.accessToken);
      setUser(response.user);
      setStatus('authenticated');
      queryClient.setQueryData(['currentUser'], response.user);
    } catch (err) {
      if (authSession.isValidEpoch(epochAtStart)) {
        tokenStore.clearAccessToken();
        setUser(null);
        setStatus('unauthenticated');
      }
      throw err;
    }
  }, []);

  const register = useCallback(async (payload: RegisterPayload) => {
    return authApi.register(payload);
  }, []);

  const logout = useCallback(() => {
    if (activeLogoutPromise) {
      return activeLogoutPromise;
    }

    // Step 1: Immediately invalidate session epoch and local state
    authSession.invalidateSession();
    tokenStore.clearAccessToken();
    setUser(null);
    setStatus('unauthenticated');
    queryClient.clear();

    activeLogoutPromise = (async () => {
      try {
        await authApi.logout();
      } catch {
        // Network failure during logout must still leave client unauthenticated locally
      } finally {
        tokenStore.clearAccessToken();
        setUser(null);
        setStatus('unauthenticated');
        queryClient.clear();
        activeLogoutPromise = null;
      }
    })();

    return activeLogoutPromise;
  }, []);

  const value: AuthContextType = {
    status,
    user,
    login,
    register,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};
