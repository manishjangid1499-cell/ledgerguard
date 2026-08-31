import { apiClient, executeSingleFlightRefresh } from '../../shared/api/apiClient';
import { UserSummary } from '../../shared/types/user.types';
import { AuthResponse, LoginCredentials, RegisterPayload } from '../types/auth.types';

export const authApi = {
  async register(payload: RegisterPayload): Promise<UserSummary> {
    return apiClient<UserSummary>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  async login(credentials: LoginCredentials): Promise<AuthResponse> {
    return apiClient<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(credentials),
    });
  },

  async refresh(): Promise<AuthResponse | null> {
    return executeSingleFlightRefresh();
  },

  async logout(): Promise<void> {
    return apiClient<void>('/api/auth/logout', {
      method: 'POST',
    });
  },

  async getMe(): Promise<UserSummary> {
    return apiClient<UserSummary>('/api/auth/me', {
      method: 'GET',
    });
  },
};
