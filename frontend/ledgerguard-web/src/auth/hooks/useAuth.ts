import { useContext } from 'react';
import { AuthContext, AuthContextType, AuthStatus } from '../../app/providers/authContext';

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}

export type { AuthStatus };
