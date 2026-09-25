import { ReactElement, ReactNode } from 'react';
import { render, RenderOptions } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, MemoryRouterProps } from 'react-router-dom';

export function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
      mutations: {
        retry: false,
      },
    },
  });
}

import { AuthContext, AuthContextType } from '../app/providers/authContext';
import { UserSummary } from '../shared/types/user.types';

export const mockMerchantUser: UserSummary = {
  id: '00000000-0000-0000-0000-000000000001',
  email: 'merchant@example.com',
  role: 'MERCHANT',
  status: 'ACTIVE',
  createdAt: '2026-09-24T00:00:00Z',
  fullName: 'Acme Merchant',
};

interface CustomRenderOptions extends Omit<RenderOptions, 'wrapper'> {
  initialEntries?: MemoryRouterProps['initialEntries'];
  queryClient?: QueryClient;
  user?: UserSummary | null;
  authStatus?: 'loading' | 'authenticated' | 'unauthenticated';
}

export function renderWithProviders(
  ui: ReactElement,
  options: CustomRenderOptions = {}
) {
  const {
    initialEntries = ['/'],
    queryClient = createTestQueryClient(),
    user = mockMerchantUser,
    authStatus = 'authenticated',
    ...renderOptions
  } = options;

  const authValue: AuthContextType = {
    status: authStatus,
    user,
    login: async () => {},
    register: async () => mockMerchantUser,
    logout: async () => {},
  };

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <AuthContext.Provider value={authValue}>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={initialEntries}>
            {children}
          </MemoryRouter>
        </QueryClientProvider>
      </AuthContext.Provider>
    );
  }

  return {
    queryClient,
    ...render(ui, { wrapper: Wrapper, ...renderOptions }),
  };
}
