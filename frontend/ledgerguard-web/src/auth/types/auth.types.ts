import { UserSummary } from '../../shared/types/user.types';

export interface AuthResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserSummary;
}

export interface LoginCredentials {
  email: string;
  password: string;
}

export interface RegisterPayload {
  email: string;
  password: string;
  role: 'CUSTOMER' | 'MERCHANT';
}
