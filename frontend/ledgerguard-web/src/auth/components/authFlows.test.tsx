import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/test-utils';
import { ForgotPasswordPage } from '../pages/ForgotPasswordPage';
import { ResetPasswordPage } from '../pages/ResetPasswordPage';
import { ChangePasswordForm } from './ChangePasswordForm';
import { LoginForm } from './LoginForm';
import { authApi } from '../api/authApi';
import { ApiError } from '../../shared/types/api.types';

describe('Password Recovery and Change Flows', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  describe('ForgotPasswordPage', () => {
    it('renders email input and submit button', () => {
      renderWithProviders(<ForgotPasswordPage />, { initialEntries: ['/forgot-password'] });
      expect(screen.getByRole('heading', { name: /reset password/i })).toBeInTheDocument();
      expect(screen.getByLabelText(/email address/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /send reset link/i })).toBeInTheDocument();
    });

    it('validates empty email', async () => {
      const user = userEvent.setup();
      renderWithProviders(<ForgotPasswordPage />, { initialEntries: ['/forgot-password'] });

      await user.click(screen.getByRole('button', { name: /send reset link/i }));
      expect(await screen.findByText(/email is required/i)).toBeInTheDocument();
    });

    it('submits email and displays generic success message', async () => {
      const user = userEvent.setup();
      const forgotPasswordSpy = vi.spyOn(authApi, 'forgotPassword').mockResolvedValue({
        message: 'If an eligible account exists, you will receive a password reset link.',
      });

      renderWithProviders(<ForgotPasswordPage />, { initialEntries: ['/forgot-password'] });

      await user.type(screen.getByLabelText(/email address/i), 'user@example.com');
      await user.click(screen.getByRole('button', { name: /send reset link/i }));

      await waitFor(() => {
        expect(forgotPasswordSpy).toHaveBeenCalledWith({ email: 'user@example.com' });
      });

      expect(
        await screen.findByText(/if an eligible account exists, you will receive a password reset link/i)
      ).toBeInTheDocument();
    });

    it('displays server error if api request fails', async () => {
      const user = userEvent.setup();
      vi.spyOn(authApi, 'forgotPassword').mockRejectedValue(
        new ApiError({ status: 500, detail: 'Server error occurred.' })
      );

      renderWithProviders(<ForgotPasswordPage />, { initialEntries: ['/forgot-password'] });

      await user.type(screen.getByLabelText(/email address/i), 'user@example.com');
      await user.click(screen.getByRole('button', { name: /send reset link/i }));

      expect(await screen.findByText(/unable to submit request/i)).toBeInTheDocument();
    });
  });

  describe('ResetPasswordPage', () => {
    it('renders warning when no token is present in URL', () => {
      renderWithProviders(<ResetPasswordPage />, { initialEntries: ['/reset-password'] });
      expect(screen.getByText(/no reset token found/i)).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /request new reset link/i })).toBeInTheDocument();
    });

    it('renders reset password form when token is present and cleans URL', async () => {
      renderWithProviders(<ResetPasswordPage />, {
        initialEntries: ['/reset-password?token=valid-secret-token-123'],
      });

      expect(screen.getByLabelText(/^new password \*/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/^confirm new password \*/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /reset password/i })).toBeInTheDocument();
    });

    it('validates password minimum length of 12 characters', async () => {
      const user = userEvent.setup();
      renderWithProviders(<ResetPasswordPage />, {
        initialEntries: ['/reset-password?token=valid-secret-token-123'],
      });

      await user.type(screen.getByLabelText(/^new password \*/i), 'short123');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'short123');
      await user.click(screen.getByRole('button', { name: /reset password/i }));

      expect(await screen.findByText(/password must be at least 12 characters/i)).toBeInTheDocument();
    });

    it('validates password confirmation mismatch', async () => {
      const user = userEvent.setup();
      renderWithProviders(<ResetPasswordPage />, {
        initialEntries: ['/reset-password?token=valid-secret-token-123'],
      });

      await user.type(screen.getByLabelText(/^new password \*/i), 'ValidPassword123!');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'DifferentPassword123!');
      await user.click(screen.getByRole('button', { name: /reset password/i }));

      expect(await screen.findByText(/passwords do not match/i)).toBeInTheDocument();
    });

    it('submits token and new password and shows success message', async () => {
      const user = userEvent.setup();
      const resetSpy = vi.spyOn(authApi, 'resetPassword').mockResolvedValue({
        message: 'Password has been reset successfully. Please log in with your new password.',
      });

      renderWithProviders(<ResetPasswordPage />, {
        initialEntries: ['/reset-password?token=valid-secret-token-123'],
      });

      await user.type(screen.getByLabelText(/^new password \*/i), 'NewStrongPassword123!');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'NewStrongPassword123!');
      await user.click(screen.getByRole('button', { name: /reset password/i }));

      await waitFor(() => {
        expect(resetSpy).toHaveBeenCalledWith({
          token: 'valid-secret-token-123',
          newPassword: 'NewStrongPassword123!',
        });
      });

      expect(
        await screen.findByText(/password has been reset successfully/i)
      ).toBeInTheDocument();
    });

    it('works when already authenticated and immediately invokes logout to clear local session and query cache', async () => {
      const user = userEvent.setup();
      const logoutSpy = vi.fn().mockResolvedValue(undefined);
      vi.spyOn(authApi, 'resetPassword').mockResolvedValue({
        message: 'Password has been reset successfully. Please log in with your new password.',
      });

      renderWithProviders(<ResetPasswordPage />, {
        initialEntries: ['/reset-password?token=authenticated-user-token'],
        authStatus: 'authenticated',
        logout: logoutSpy,
      });

      // Confirm page renders form even when authenticated
      expect(screen.getByLabelText(/^new password \*/i)).toBeInTheDocument();

      await user.type(screen.getByLabelText(/^new password \*/i), 'NewStrongPassword123!');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'NewStrongPassword123!');
      await user.click(screen.getByRole('button', { name: /reset password/i }));

      await waitFor(() => {
        expect(logoutSpy).toHaveBeenCalled();
      });

      expect(
        await screen.findByText(/password has been reset successfully/i)
      ).toBeInTheDocument();
    });

    it('preserves router history state when removing reset token from URL', () => {
      const replaceStateSpy = vi.spyOn(window.history, 'replaceState');
      renderWithProviders(<ResetPasswordPage />, {
        initialEntries: ['/reset-password?token=scrub-test-token'],
      });

      expect(replaceStateSpy).toHaveBeenCalledWith(
        window.history.state,
        '',
        expect.any(String)
      );
    });

    it('displays specific invalid token message and link to /forgot-password when API returns INVALID_PASSWORD_RESET_TOKEN', async () => {
      const user = userEvent.setup();
      vi.spyOn(authApi, 'resetPassword').mockRejectedValue(
        new ApiError({
          status: 400,
          title: 'Invalid reset token',
          detail: 'Invalid, expired, or already used reset token.',
          errorCode: 'INVALID_PASSWORD_RESET_TOKEN',
        })
      );

      renderWithProviders(<ResetPasswordPage />, {
        initialEntries: ['/reset-password?token=expired-or-consumed-token-123'],
      });

      await user.type(screen.getByLabelText(/^new password \*/i), 'NewStrongPassword123!');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'NewStrongPassword123!');
      await user.click(screen.getByRole('button', { name: /reset password/i }));

      // Assert specific message is displayed
      expect(
        await screen.findByText(/this reset link is invalid, expired, or already used\./i)
      ).toBeInTheDocument();

      // Assert link to /forgot-password with exact text
      const requestLink = screen.getByRole('link', { name: /request a new link\./i });
      expect(requestLink).toBeInTheDocument();
      expect(requestLink).toHaveAttribute('href', '/forgot-password');
    });

    it('preserves field-specific validation error messages when password does not meet criteria', async () => {
      const user = userEvent.setup();
      renderWithProviders(<ResetPasswordPage />, {
        initialEntries: ['/reset-password?token=some-valid-token-123'],
      });

      await user.type(screen.getByLabelText(/^new password \*/i), 'short');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'short');
      await user.click(screen.getByRole('button', { name: /reset password/i }));

      expect(await screen.findByText('Password must be at least 12 characters.')).toBeInTheDocument();
      expect(screen.queryByText(/this reset link is invalid/i)).not.toBeInTheDocument();
    });
  });

  describe('ChangePasswordForm', () => {
    it('renders current, new, and confirm password fields', () => {
      renderWithProviders(<ChangePasswordForm />);
      expect(screen.getByLabelText(/^current password \*/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/^new password \*/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/^confirm new password \*/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /update password/i })).toBeInTheDocument();
    });

    it('validates required fields and password length', async () => {
      const user = userEvent.setup();
      renderWithProviders(<ChangePasswordForm />);

      await user.type(screen.getByLabelText(/^current password \*/i), 'CurrentPass123!');
      await user.type(screen.getByLabelText(/^new password \*/i), 'short');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'short');
      await user.click(screen.getByRole('button', { name: /update password/i }));

      expect(await screen.findByText(/password must be at least 12 characters/i)).toBeInTheDocument();
    });

    it('submits valid change password payload and displays success message', async () => {
      const user = userEvent.setup();
      const changeSpy = vi.spyOn(authApi, 'changePassword').mockResolvedValue({
        message: 'Password has been changed successfully. Please log in again.',
      });

      renderWithProviders(<ChangePasswordForm />);

      await user.type(screen.getByLabelText(/^current password \*/i), 'OldPassword123!');
      await user.type(screen.getByLabelText(/^new password \*/i), 'BrandNewPassword123!');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'BrandNewPassword123!');
      await user.click(screen.getByRole('button', { name: /update password/i }));

      await waitFor(() => {
        expect(changeSpy).toHaveBeenCalledWith({
          currentPassword: 'OldPassword123!',
          newPassword: 'BrandNewPassword123!',
        });
      });

      expect(
        await screen.findByText(/password has been changed successfully/i)
      ).toBeInTheDocument();
    });

    it('immediately invokes logout to clear local session and query cache upon password change', async () => {
      const user = userEvent.setup();
      const logoutSpy = vi.fn().mockResolvedValue(undefined);
      vi.spyOn(authApi, 'changePassword').mockResolvedValue({
        message: 'Password has been changed successfully. Please log in again.',
      });

      renderWithProviders(<ChangePasswordForm />, {
        logout: logoutSpy,
      });

      await user.type(screen.getByLabelText(/^current password \*/i), 'OldPassword123!');
      await user.type(screen.getByLabelText(/^new password \*/i), 'BrandNewPassword123!');
      await user.type(screen.getByLabelText(/^confirm new password \*/i), 'BrandNewPassword123!');
      await user.click(screen.getByRole('button', { name: /update password/i }));

      await waitFor(() => {
        expect(logoutSpy).toHaveBeenCalled();
      });
    });
  });

  describe('LoginForm', () => {
    it('contains link to /forgot-password', () => {
      renderWithProviders(<LoginForm />, { initialEntries: ['/login'] });
      const forgotLink = screen.getByRole('link', { name: /forgot password\?/i });
      expect(forgotLink).toBeInTheDocument();
      expect(forgotLink).toHaveAttribute('href', '/forgot-password');
    });
  });

  describe('Authentication Independence of Public Password Recovery Endpoints', () => {
    it('executes forgot-password and reset-password without Bearer token or automatic token refresh when expired or revoked access token exists in memory', async () => {
      const { tokenStore } = await import('../api/tokenStore');

      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
        const url = String(input);
        if (url.includes('/api/auth/forgot-password') || url.includes('/api/auth/reset-password')) {
          return new Response(JSON.stringify({ message: 'Success response.' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        if (url.includes('/api/auth/change-password')) {
          return new Response(JSON.stringify({ message: 'Password changed.' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          });
        }
        return new Response(null, { status: 404 });
      });

      // Set an expired or revoked access token in local in-memory token store
      tokenStore.setAccessToken('expired-or-revoked-local-token');

      // 1. forgotPassword call
      await authApi.forgotPassword({ email: 'independent@example.com' });
      const forgotCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/api/auth/forgot-password'));
      expect(forgotCall).toBeDefined();
      const forgotHeaders = new Headers(forgotCall?.[1]?.headers);
      expect(forgotHeaders.has('Authorization')).toBe(false);

      // 2. resetPassword call
      await authApi.resetPassword({ token: 'reset-token-xyz', newPassword: 'NewValidPassword123!' });
      const resetCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/api/auth/reset-password'));
      expect(resetCall).toBeDefined();
      const resetHeaders = new Headers(resetCall?.[1]?.headers);
      expect(resetHeaders.has('Authorization')).toBe(false);

      // 3. changePassword call MUST still attach Authorization header
      await authApi.changePassword({ currentPassword: 'OldPassword123!', newPassword: 'NewValidPassword123!' });
      const changeCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/api/auth/change-password'));
      expect(changeCall).toBeDefined();
      const changeHeaders = new Headers(changeCall?.[1]?.headers);
      expect(changeHeaders.get('Authorization')).toBe('Bearer expired-or-revoked-local-token');

      // Clean up tokenStore
      tokenStore.clearAccessToken();
      fetchSpy.mockRestore();
    });

    it('does not attempt single-flight refresh on 401 response from public forgot-password or reset-password', async () => {
      const { tokenStore } = await import('../api/tokenStore');
      const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
        const url = String(input);
        if (url.includes('/api/auth/refresh')) {
          throw new Error('Refresh should never be called for public password recovery endpoints!');
        }
        return new Response(JSON.stringify({ status: 401, title: 'Unauthorized', detail: 'Invalid token' }), {
          status: 401,
          headers: { 'Content-Type': 'application/problem+json' },
        });
      });

      tokenStore.setAccessToken('expired-or-revoked-local-token');

      // Public requests reject with ApiError directly without calling /api/auth/refresh
      await expect(authApi.forgotPassword({ email: 'bad@example.com' })).rejects.toThrow();
      await expect(authApi.resetPassword({ token: 'bad-token', newPassword: 'NewPassword123!' })).rejects.toThrow();

      const refreshCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/api/auth/refresh'));
      expect(refreshCall).toBeUndefined();

      tokenStore.clearAccessToken();
      fetchSpy.mockRestore();
    });
  });
});
