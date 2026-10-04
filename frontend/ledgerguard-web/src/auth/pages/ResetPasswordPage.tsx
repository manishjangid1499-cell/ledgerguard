import React, { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import {
  Box,
  TextField,
  Button,
  Alert,
  CircularProgress,
  InputAdornment,
  IconButton,
  Stack,
  Typography,
  Link,
} from '@mui/material';
import Visibility from '@mui/icons-material/Visibility';
import VisibilityOff from '@mui/icons-material/VisibilityOff';
import { useNavigate, useSearchParams, Link as RouterLink } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { useAuth } from '../hooks/useAuth';
import { AuthCard } from '../components/AuthCard';
import { getErrorMessage } from '../../shared/api/errorMessage';
import { ApiError } from '../../shared/types/api.types';

interface ResetPasswordFormValues {
  newPassword: string;
  confirmPassword: string;
}

export const ResetPasswordPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { logout } = useAuth();
  const [token, setToken] = useState<string | null>(() => searchParams.get('token'));
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [isTokenInvalid, setIsTokenInvalid] = useState(false);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const submitting = useRef(false);

  // Capture token in memory and clean URL to prevent leakage via referrers or history
  useEffect(() => {
    const rawToken = searchParams.get('token');
    if (rawToken) {
      setToken(rawToken);
      window.history.replaceState(window.history.state, '', window.location.pathname);
    }
  }, [searchParams]);

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordFormValues>({
    defaultValues: {
      newPassword: '',
      confirmPassword: '',
    },
  });

  const newPasswordValue = watch('newPassword');

  const onSubmit = async (data: ResetPasswordFormValues) => {
    if (submitting.current) return;
    if (!token) {
      setIsTokenInvalid(true);
      return;
    }

    submitting.current = true;
    setServerError(null);
    setIsTokenInvalid(false);
    try {
      const response = await authApi.resetPassword({
        token,
        newPassword: data.newPassword,
      });
      // Immediately clear local authentication and private query-cache state
      try {
        await logout();
      } catch {
        // Suppress any logout failure since password was reset
      }
      setSuccessMessage(
        response.message || 'Password has been reset successfully. Please log in with your new password.'
      );
      // Navigate to login after brief delay
      setTimeout(() => {
        navigate('/login', {
          replace: true,
          state: {
            message: 'Password reset successfully. Please sign in with your new password.',
          },
        });
      }, 1500);
    } catch (err) {
      if (err instanceof ApiError && err.problem?.errorCode === 'INVALID_PASSWORD_RESET_TOKEN') {
        setIsTokenInvalid(true);
      } else {
        setServerError(getErrorMessage(err, 'Unable to reset password. The link may have expired.'));
      }
    } finally {
      submitting.current = false;
    }
  };

  return (
    <AuthCard
      title="Create new password"
      description="Choose a new, secure password for your LedgerGuard account."
      alternateText="Back to"
      alternateLabel="Sign in"
      alternateRoute="/login"
    >
      {!token && !successMessage ? (
        <Stack spacing={2.5} sx={{ mt: 1 }}>
          <Alert severity="warning" sx={{ borderRadius: '10px', fontSize: '0.875rem' }}>
            No reset token found or the link has already been opened. If you need to reset your password, please request a new link.
          </Alert>
          <Button
            component={RouterLink}
            to="/forgot-password"
            variant="contained"
            fullWidth
            sx={{
              py: 1.4,
              borderRadius: '10px',
              fontWeight: 600,
              bgcolor: 'primary.main',
            }}
          >
            Request new reset link
          </Button>
        </Stack>
      ) : successMessage ? (
        <Stack spacing={2.5} sx={{ mt: 1 }}>
          <Alert severity="success" sx={{ borderRadius: '10px', fontSize: '0.875rem' }}>
            {successMessage}
          </Alert>
          <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center' }}>
            Redirecting to sign in…
          </Typography>
        </Stack>
      ) : (
        <Box
          component="form"
          onSubmit={handleSubmit(onSubmit)}
          noValidate
          aria-busy={isSubmitting}
          sx={{ mt: 0.5 }}
        >
          <Stack spacing={2}>
            {isTokenInvalid && (
              <Alert
                severity="error"
                onClose={() => setIsTokenInvalid(false)}
                sx={{ borderRadius: '10px', fontSize: '0.875rem' }}
              >
                This reset link is invalid, expired, or already used.{' '}
                <Link
                  component={RouterLink}
                  to="/forgot-password"
                  underline="hover"
                  sx={{ fontWeight: 600, color: 'inherit', textDecoration: 'underline' }}
                >
                  Request a new link.
                </Link>
              </Alert>
            )}

            {serverError && (
              <Alert
                severity="error"
                onClose={() => setServerError(null)}
                sx={{ borderRadius: '10px', fontSize: '0.875rem' }}
              >
                {serverError}
              </Alert>
            )}

            <Box sx={{ textAlign: 'left' }}>
              <Typography
                component="label"
                htmlFor="newPassword"
                sx={{
                  display: 'block',
                  fontSize: '0.8125rem',
                  fontWeight: 600,
                  color: 'text.primary',
                  mb: 0.5,
                }}
              >
                New password <Box component="span" sx={{ color: 'error.main' }}>*</Box>
              </Typography>
              <TextField
                {...register('newPassword', {
                  required: 'New password is required.',
                  minLength: {
                    value: 12,
                    message: 'Password must be at least 12 characters.',
                  },
                  validate: (value) => {
                    const byteLength = new TextEncoder().encode(value).length;
                    return byteLength <= 72 || 'Password must not exceed 72 UTF-8 bytes.';
                  },
                })}
                id="newPassword"
                type={showPassword ? 'text' : 'password'}
                autoComplete="new-password"
                hiddenLabel
                autoFocus
                fullWidth
                error={Boolean(errors.newPassword)}
                helperText={errors.newPassword?.message}
                disabled={isSubmitting}
                sx={{
                  '& .MuiOutlinedInput-root': {
                    borderRadius: '10px',
                    bgcolor: '#ffffff',
                  },
                }}
                slotProps={{
                  input: {
                    endAdornment: (
                      <InputAdornment position="end">
                        <IconButton
                          type="button"
                          disabled={isSubmitting}
                          aria-pressed={showPassword}
                          aria-label={showPassword ? 'Hide password' : 'Show password'}
                          onClick={() => setShowPassword((prev) => !prev)}
                          edge="end"
                          size="small"
                        >
                          {showPassword ? <VisibilityOff fontSize="small" /> : <Visibility fontSize="small" />}
                        </IconButton>
                      </InputAdornment>
                    ),
                  },
                }}
              />
            </Box>

            <Box sx={{ textAlign: 'left' }}>
              <Typography
                component="label"
                htmlFor="confirmPassword"
                sx={{
                  display: 'block',
                  fontSize: '0.8125rem',
                  fontWeight: 600,
                  color: 'text.primary',
                  mb: 0.5,
                }}
              >
                Confirm new password <Box component="span" sx={{ color: 'error.main' }}>*</Box>
              </Typography>
              <TextField
                {...register('confirmPassword', {
                  required: 'Please confirm your new password.',
                  validate: (value) =>
                    value === newPasswordValue || 'Passwords do not match.',
                })}
                id="confirmPassword"
                type={showConfirmPassword ? 'text' : 'password'}
                autoComplete="new-password"
                hiddenLabel
                fullWidth
                error={Boolean(errors.confirmPassword)}
                helperText={errors.confirmPassword?.message}
                disabled={isSubmitting}
                sx={{
                  '& .MuiOutlinedInput-root': {
                    borderRadius: '10px',
                    bgcolor: '#ffffff',
                  },
                }}
                slotProps={{
                  input: {
                    endAdornment: (
                      <InputAdornment position="end">
                        <IconButton
                          type="button"
                          disabled={isSubmitting}
                          aria-pressed={showConfirmPassword}
                          aria-label={showConfirmPassword ? 'Hide password' : 'Show password'}
                          onClick={() => setShowConfirmPassword((prev) => !prev)}
                          edge="end"
                          size="small"
                        >
                          {showConfirmPassword ? <VisibilityOff fontSize="small" /> : <Visibility fontSize="small" />}
                        </IconButton>
                      </InputAdornment>
                    ),
                  },
                }}
              />
            </Box>

            <Button
              type="submit"
              fullWidth
              variant="contained"
              size="large"
              disabled={isSubmitting}
              sx={{
                py: 1.4,
                minHeight: 48,
                borderRadius: '10px',
                fontWeight: 600,
                fontSize: '0.9375rem',
                bgcolor: 'primary.main',
                boxShadow: '0 1px 2px 0 rgba(15, 41, 66, 0.08)',
                '&:hover': {
                  bgcolor: '#163e65',
                  boxShadow: '0 4px 12px 0 rgba(15, 41, 66, 0.15)',
                },
              }}
              startIcon={
                isSubmitting ? <CircularProgress size={18} color="inherit" aria-hidden="true" /> : undefined
              }
            >
              {isSubmitting ? 'Resetting password…' : 'Reset password'}
            </Button>
          </Stack>
        </Box>
      )}
    </AuthCard>
  );
};
