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
} from '@mui/material';
import Visibility from '@mui/icons-material/Visibility';
import VisibilityOff from '@mui/icons-material/VisibilityOff';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { LoginCredentials } from '../types/auth.types';
import { ApiError } from '../../shared/types/api.types';
import { getErrorMessage } from '../../shared/api/errorMessage';

export const LoginForm: React.FC = () => {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [showPassword, setShowPassword] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const submitting = useRef(false);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginCredentials>({
    defaultValues: {
      email: '',
      password: '',
    },
  });

  const onSubmit = async (data: LoginCredentials) => {
    if (submitting.current) return;
    submitting.current = true;
    setServerError(null);
    try {
      await login(data);
      navigate('/app');
    } catch (err) {
      setServerError(err instanceof ApiError && err.status === 401
        ? 'The email or password is incorrect.'
        : getErrorMessage(err, 'Unable to sign in right now. Please try again.'));
    } finally {
      submitting.current = false;
    }
  };

  return (
    <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate aria-busy={isSubmitting} sx={{ mt: 1 }}>
      <Stack spacing={2.5}>
        {serverError && (
          <Alert severity="error" onClose={() => setServerError(null)}>
            {serverError}
          </Alert>
        )}

        <TextField
          {...register('email', {
            required: 'Email is required',
            pattern: {
              value: /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i,
              message: 'Enter a valid email address',
            },
          })}
          id="email"
          label="Email address"
          type="email"
          autoComplete="email"
          required
          autoFocus
          fullWidth
          error={Boolean(errors.email)}
          helperText={errors.email?.message}
          disabled={isSubmitting}
        />

        <TextField
          {...register('password', {
            required: 'Password is required',
            validate: (value) => value.trim().length > 0 || 'Password is required',
          })}
          id="password"
          label="Password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="current-password"
          required
          fullWidth
          error={Boolean(errors.password)}
          helperText={errors.password?.message}
          disabled={isSubmitting}
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
                  >
                    {showPassword ? <VisibilityOff /> : <Visibility />}
                  </IconButton>
                </InputAdornment>
              ),
            },
          }}
        />

        <Button
          type="submit"
          fullWidth
          variant="contained"
          size="large"
          disabled={isSubmitting}
          sx={{ py: 1.3 }}
          startIcon={isSubmitting ? <CircularProgress size={18} color="inherit" aria-hidden="true" /> : undefined}
        >
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </Button>
      </Stack>
    </Box>
  );
};
