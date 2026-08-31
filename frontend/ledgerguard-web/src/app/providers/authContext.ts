import { createContext } from 'react';
import { LoginCredentials, RegisterPayload } from '../../auth/types/auth.types';
import { UserSummary } from '../../shared/types/user.types';

export type AuthStatus = 'loading' | 'authenticated' | 'unauthenticated';

export interface AuthContextType {
  status: AuthStatus;
  user: UserSummary | null;
  login: (credentials: LoginCredentials) => Promise<void>;
  register: (payload: RegisterPayload) => Promise<UserSummary>;
  logout: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined);
