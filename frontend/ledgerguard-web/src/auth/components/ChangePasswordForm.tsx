import React, { useRef, useState } from 'react';
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
} from '@mui/material';
import Visibility from '@mui/icons-material/Visibility';
import VisibilityOff from '@mui/icons-material/VisibilityOff';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/authApi';
import { useAuth } from '../hooks/useAuth';
import { getErrorMessage } from '../../shared/api/errorMessage';

interface ChangePasswordFormValues {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export const ChangePasswordForm: React.FC = () => {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [showCurrent, setShowCurrent] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const submitting = useRef(false);

  const {
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordFormValues>({
    defaultValues: {
      currentPassword: '',
      newPassword: '',
      confirmPassword: '',
    },
  });

  const newPasswordValue = watch('newPassword');

  const onSubmit = async (data: ChangePasswordFormValues) => {
    if (submitting.current) return;
    submitting.current = true;
    setServerError(null);
    setSuccessMessage(null);
    try {
      const response = await authApi.changePassword({
        currentPassword: data.currentPassword,
        newPassword: data.newPassword,
      });
      // Immediately clear local authentication and private query-cache state
      try {
        await logout();
      } catch {
        // Ignore logout error if session already revoked
      }
      setSuccessMessage(
        response.message || 'Password changed successfully. Logging out…'
      );
      reset();
      setTimeout(() => {
        navigate('/login', {
          replace: true,
          state: {
            message: 'Password changed successfully. Please log in with your new password.',
          },
        });
      }, 1500);
    } catch (err) {
      setServerError(getErrorMessage(err, 'Unable to change password. Please verify your current password.'));
    } finally {
      submitting.current = false;
    }
  };

  return (
    <Box
      component="form"
      onSubmit={handleSubmit(onSubmit)}
      noValidate
      aria-busy={isSubmitting}
      sx={{ maxWidth: 480 }}
    >
      <Stack spacing={2.5}>
        {serverError && (
          <Alert
            severity="error"
            onClose={() => setServerError(null)}
            sx={{ borderRadius: '10px', fontSize: '0.875rem' }}
          >
            {serverError}
          </Alert>
        )}

        {successMessage && (
          <Alert
            severity="success"
            sx={{ borderRadius: '10px', fontSize: '0.875rem' }}
          >
            {successMessage}
          </Alert>
        )}

        <Box sx={{ textAlign: 'left' }}>
          <Typography
            component="label"
            htmlFor="currentPassword"
            sx={{
              display: 'block',
              fontSize: '0.8125rem',
              fontWeight: 600,
              color: 'text.primary',
              mb: 0.5,
            }}
          >
            Current password <Box component="span" sx={{ color: 'error.main' }}>*</Box>
          </Typography>
          <TextField
            {...register('currentPassword', {
              required: 'Current password is required.',
            })}
            id="currentPassword"
            type={showCurrent ? 'text' : 'password'}
            autoComplete="current-password"
            hiddenLabel
            fullWidth
            error={Boolean(errors.currentPassword)}
            helperText={errors.currentPassword?.message}
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
                      aria-pressed={showCurrent}
                      aria-label={showCurrent ? 'Hide current password' : 'Show current password'}
                      onClick={() => setShowCurrent((prev) => !prev)}
                      edge="end"
                      size="small"
                    >
                      {showCurrent ? <VisibilityOff fontSize="small" /> : <Visibility fontSize="small" />}
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
            type={showNew ? 'text' : 'password'}
            autoComplete="new-password"
            hiddenLabel
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
                      aria-pressed={showNew}
                      aria-label={showNew ? 'Hide new password' : 'Show new password'}
                      onClick={() => setShowNew((prev) => !prev)}
                      edge="end"
                      size="small"
                    >
                      {showNew ? <VisibilityOff fontSize="small" /> : <Visibility fontSize="small" />}
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
            type={showConfirm ? 'text' : 'password'}
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
                      aria-pressed={showConfirm}
                      aria-label={showConfirm ? 'Hide confirm password' : 'Show confirm password'}
                      onClick={() => setShowConfirm((prev) => !prev)}
                      edge="end"
                      size="small"
                    >
                      {showConfirm ? <VisibilityOff fontSize="small" /> : <Visibility fontSize="small" />}
                    </IconButton>
                  </InputAdornment>
                ),
              },
            }}
          />
        </Box>

        <Button
          type="submit"
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
          {isSubmitting ? 'Updating password…' : 'Update password'}
        </Button>
      </Stack>
    </Box>
  );
};
