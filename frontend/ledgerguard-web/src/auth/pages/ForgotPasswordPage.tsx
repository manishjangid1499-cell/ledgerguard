import React, { useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import {
  Box,
  TextField,
  Button,
  Alert,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import { authApi } from '../api/authApi';
import { ForgotPasswordPayload } from '../types/auth.types';
import { AuthCard } from '../components/AuthCard';
import { getErrorMessage } from '../../shared/api/errorMessage';

export const ForgotPasswordPage: React.FC = () => {
  const [serverError, setServerError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const submitting = useRef(false);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordPayload>({
    defaultValues: {
      email: '',
    },
  });

  const onSubmit = async (data: ForgotPasswordPayload) => {
    if (submitting.current) return;
    submitting.current = true;
    setServerError(null);
    try {
      const response = await authApi.forgotPassword({ email: data.email.trim() });
      setSuccessMessage(
        response.message || 'If an eligible account exists, you will receive a password reset link.'
      );
    } catch (err) {
      setServerError(getErrorMessage(err, 'Unable to submit request. Please try again.'));
    } finally {
      submitting.current = false;
    }
  };

  return (
    <AuthCard
      title="Reset password"
      description="Enter your email address to receive a secure password reset link."
      alternateText="Remember your password?"
      alternateLabel="Sign in"
      alternateRoute="/login"
    >
      {successMessage ? (
        <Stack spacing={2.5} sx={{ mt: 1 }}>
          <Alert severity="success" sx={{ borderRadius: '10px', fontSize: '0.875rem' }}>
            {successMessage}
          </Alert>
          <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center' }}>
            Check your inbox for instructions. If the email doesn't arrive within a few minutes, check your spam folder.
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
                htmlFor="email"
                sx={{
                  display: 'block',
                  fontSize: '0.8125rem',
                  fontWeight: 600,
                  color: 'text.primary',
                  mb: 0.5,
                }}
              >
                Email address <Box component="span" sx={{ color: 'error.main' }}>*</Box>
              </Typography>
              <TextField
                {...register('email', {
                  required: 'Email is required.',
                  pattern: {
                    value: /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i,
                    message: 'Enter a valid email address.',
                  },
                })}
                id="email"
                type="email"
                autoComplete="email"
                hiddenLabel
                autoFocus
                fullWidth
                error={Boolean(errors.email)}
                helperText={errors.email?.message}
                disabled={isSubmitting}
                sx={{
                  '& .MuiOutlinedInput-root': {
                    borderRadius: '10px',
                    bgcolor: '#ffffff',
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
              {isSubmitting ? 'Sending link…' : 'Send reset link'}
            </Button>
          </Stack>
        </Box>
      )}
    </AuthCard>
  );
};
