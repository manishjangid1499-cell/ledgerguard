import React, { useEffect, useState, useCallback } from 'react';
import { authApi } from '../../auth/api/authApi';
import { tokenStore } from '../../auth/api/tokenStore';
import { LoginCredentials, RegisterPayload } from '../../auth/types/auth.types';
import { UserSummary } from '../../shared/types/user.types';
import { AuthContext, AuthStatus, AuthContextType } from './authContext';
import { queryClient } from './queryClient';

interface AuthProviderProps {
  children: React.ReactNode;
}

export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<UserSummary | null>(null);

  // Restore session on initial application load via HttpOnly refresh cookie
  useEffect(() => {
    let isMounted = true;

    async function restoreSession() {
      try {
        const response = await authApi.refresh();
        if (response?.accessToken && response?.user) {
          if (isMounted) {
            tokenStore.setAccessToken(response.accessToken);
            setUser(response.user);
            setStatus('authenticated');
            queryClient.setQueryData(['currentUser'], response.user);
          }
        } else {
          if (isMounted) {
            tokenStore.clearAccessToken();
            setUser(null);
            setStatus('unauthenticated');
          }
        }
      } catch {
        if (isMounted) {
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
    const response = await authApi.login(credentials);
    tokenStore.setAccessToken(response.accessToken);
    setUser(response.user);
    setStatus('authenticated');
    queryClient.setQueryData(['currentUser'], response.user);
  }, []);

  const register = useCallback(async (payload: RegisterPayload) => {
    return authApi.register(payload);
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // Treat logout as complete locally regardless of backend network state
    } finally {
      tokenStore.clearAccessToken();
      setUser(null);
      setStatus('unauthenticated');
      queryClient.clear();
    }
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
