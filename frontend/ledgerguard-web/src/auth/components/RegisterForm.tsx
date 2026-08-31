import React, { useState } from 'react';
import { useForm, Controller } from 'react-hook-form';
import {
  Box,
  TextField,
  Button,
  Alert,
  CircularProgress,
  InputAdornment,
  IconButton,
  Stack,
  FormControl,
  FormLabel,
  RadioGroup,
  FormControlLabel,
  Radio,
  FormHelperText,
} from '@mui/material';
import Visibility from '@mui/icons-material/Visibility';
import VisibilityOff from '@mui/icons-material/VisibilityOff';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { RegisterPayload } from '../types/auth.types';
import { ApiError } from '../../shared/types/api.types';

export const RegisterForm: React.FC = () => {
  const { register: registerUser } = useAuth();
  const navigate = useNavigate();
  const [showPassword, setShowPassword] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    control,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterPayload>({
    defaultValues: {
      email: '',
      password: '',
      role: 'CUSTOMER',
    },
  });

  const onSubmit = async (data: RegisterPayload) => {
    setServerError(null);
    try {
      await registerUser(data);
      navigate('/login', {
        state: { message: 'Account created successfully. Sign in to continue.' },
      });
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.problem.errors && err.problem.errors.length > 0) {
          err.problem.errors.forEach((fieldErr) => {
            if (fieldErr.field === 'email' || fieldErr.field === 'password' || fieldErr.field === 'role') {
              setError(fieldErr.field, { message: fieldErr.message });
            }
          });
        }
        setServerError(err.problem.detail || 'Registration failed. Please review your input.');
      } else {
        setServerError('An unexpected error occurred. Please try again.');
      }
    }
  };

  return (
    <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate sx={{ mt: 1 }}>
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
          autoFocus
          fullWidth
          error={Boolean(errors.email)}
          helperText={errors.email?.message}
          disabled={isSubmitting}
        />

        <TextField
          {...register('password', {
            required: 'Password is required',
            minLength: {
              value: 12,
              message: 'Password must be at least 12 characters',
            },
            validate: (val) => {
              const byteLength = new TextEncoder().encode(val).length;
              if (byteLength > 72) {
                return 'Password must not exceed 72 bytes';
              }
              return true;
            },
          })}
          id="password"
          label="Password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="new-password"
          fullWidth
          error={Boolean(errors.password)}
          helperText={errors.password?.message || 'Minimum 12 characters.'}
          disabled={isSubmitting}
          slotProps={{
            input: {
              endAdornment: (
                <InputAdornment position="end">
                  <IconButton
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

        <FormControl component="fieldset" error={Boolean(errors.role)} disabled={isSubmitting}>
          <FormLabel component="legend" sx={{ fontSize: '0.875rem', fontWeight: 600, mb: 0.5 }}>
            Account type
          </FormLabel>
          <Controller
            name="role"
            control={control}
            rules={{ required: 'Account type is required' }}
            render={({ field }) => (
              <RadioGroup {...field} row aria-label="account type">
                <FormControlLabel value="CUSTOMER" control={<Radio size="small" />} label="Customer" />
                <FormControlLabel value="MERCHANT" control={<Radio size="small" />} label="Merchant" />
              </RadioGroup>
            )}
          />
          {errors.role && <FormHelperText>{errors.role.message}</FormHelperText>}
        </FormControl>

        <Button
          type="submit"
          fullWidth
          variant="contained"
          size="large"
          disabled={isSubmitting}
          sx={{ py: 1.3 }}
        >
          {isSubmitting ? <CircularProgress size={24} color="inherit" /> : 'Create account'}
        </Button>
      </Stack>
    </Box>
  );
};
