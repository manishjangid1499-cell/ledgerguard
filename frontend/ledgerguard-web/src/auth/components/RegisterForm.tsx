import React, { useRef, useState } from 'react';
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
  Typography,
} from '@mui/material';
import Visibility from '@mui/icons-material/Visibility';
import VisibilityOff from '@mui/icons-material/VisibilityOff';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { RegisterPayload } from '../types/auth.types';
import { ApiError } from '../../shared/types/api.types';
import { getErrorMessage } from '../../shared/api/errorMessage';

interface RegisterFormData extends RegisterPayload {
  fullName: string;
  confirmPassword: string;
}

export const RegisterForm: React.FC = () => {
  const { register: registerUser } = useAuth();
  const navigate = useNavigate();
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const submitting = useRef(false);

  const {
    register,
    handleSubmit,
    control,
    setError,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<RegisterFormData>({
    defaultValues: {
      fullName: '',
      email: '',
      password: '',
      confirmPassword: '',
      role: 'CUSTOMER',
    },
  });

  const onSubmit = async (data: RegisterFormData) => {
    if (submitting.current) return;
    submitting.current = true;
    setServerError(null);
    try {
      const createdUser = await registerUser({
        fullName: data.fullName.trim(),
        email: data.email,
        password: data.password,
        role: data.role,
      });
      if (!createdUser || typeof createdUser.id !== 'string' || !createdUser.id
        || typeof createdUser.email !== 'string' || createdUser.role !== data.role) {
        setServerError('We could not confirm account creation. Try signing in with your email before creating another account.');
        return;
      }
      navigate('/login', {
        state: { message: 'Account created. Sign in to continue.' },
      });
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.problem.errors && err.problem.errors.length > 0) {
          err.problem.errors.forEach((fieldErr) => {
            if (fieldErr.field === 'fullName' || fieldErr.field === 'email' || fieldErr.field === 'password' || fieldErr.field === 'role') {
              const isTooLong = fieldErr.message?.toLowerCase().includes('max') ||
                fieldErr.message?.toLowerCase().includes('72') ||
                fieldErr.message?.toLowerCase().includes('byte') ||
                fieldErr.message?.toLowerCase().includes('long');
              const messages: Record<string, string> = {
                fullName: fieldErr.message || 'Full name must be between 2 and 120 characters.',
                email: 'Enter a valid email address.',
                password: isTooLong ? 'Password is too long.' : 'Use at least 12 characters.',
                role: 'Choose Customer or Merchant.',
              };
              setError(fieldErr.field as keyof RegisterFormData, { message: messages[fieldErr.field] }, { shouldFocus: true });
            }
          });
        }
        if (err.problem.errorCode === 'EMAIL_ALREADY_REGISTERED') {
          setError('email', { message: 'This email is already registered.' }, { shouldFocus: true });
        }
        setServerError(err.status === 0 || err.status >= 500
          ? 'We could not confirm account creation. Try signing in with your email before creating another account.'
          : getErrorMessage(err, 'Unable to create your account right now. Please try again.'));
      } else {
        setServerError('Unable to create your account right now. Please try again.');
      }
    } finally {
      submitting.current = false;
    }
  };

  return (
    <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate aria-busy={isSubmitting} sx={{ mt: 0.5 }}>
      <Stack spacing={2}>
        {serverError && (
          <Alert severity="error" onClose={() => setServerError(null)}>
            {serverError}
          </Alert>
        )}

        <TextField
          {...register('fullName', {
            required: 'Full name is required.',
            validate: (val) => {
              const trimmed = val.trim();
              if (!trimmed) return 'Full name is required.';
              if (trimmed.length < 2 || trimmed.length > 120) {
                return 'Full name must be between 2 and 120 characters.';
              }
              return true;
            },
          })}
          id="fullName"
          label="Full name"
          type="text"
          autoComplete="name"
          required
          autoFocus
          fullWidth
          error={Boolean(errors.fullName)}
          helperText={errors.fullName?.message}
          disabled={isSubmitting}
        />

        <TextField
          {...register('email', {
            required: 'Email is required.',
            pattern: {
              value: /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i,
              message: 'Enter a valid email address.',
            },
          })}
          id="email"
          label="Email address"
          type="email"
          autoComplete="email"
          required
          fullWidth
          error={Boolean(errors.email)}
          helperText={errors.email?.message}
          disabled={isSubmitting}
        />

        <TextField
          {...register('password', {
            required: 'Password is required.',
            minLength: {
              value: 12,
              message: 'Use at least 12 characters.',
            },
            validate: (val) => {
              if (!val.trim()) return 'Password is required.';
              const byteLength = new TextEncoder().encode(val).length;
              if (byteLength > 72) {
                return 'Password is too long.';
              }
              return true;
            },
          })}
          id="password"
          label="Password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="new-password"
          required
          fullWidth
          error={Boolean(errors.password)}
          helperText={errors.password?.message || 'Use at least 12 characters.'}
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
                    size="small"
                  >
                    {showPassword ? <VisibilityOff fontSize="small" /> : <Visibility fontSize="small" />}
                  </IconButton>
                </InputAdornment>
              ),
            },
          }}
        />

        <TextField
          {...register('confirmPassword', {
            required: 'Confirm password is required.',
            validate: (val) => val === watch('password') || 'Passwords do not match.',
          })}
          id="confirmPassword"
          label="Confirm password"
          type={showConfirmPassword ? 'text' : 'password'}
          autoComplete="new-password"
          required
          fullWidth
          error={Boolean(errors.confirmPassword)}
          helperText={errors.confirmPassword?.message}
          disabled={isSubmitting}
          slotProps={{
            input: {
              endAdornment: (
                <InputAdornment position="end">
                  <IconButton
                    type="button"
                    disabled={isSubmitting}
                    aria-pressed={showConfirmPassword}
                    aria-label={showConfirmPassword ? 'Hide confirm password' : 'Show confirm password'}
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

        <FormControl component="fieldset" error={Boolean(errors.role)} disabled={isSubmitting} fullWidth>
          <FormLabel component="legend" sx={{ fontSize: '0.8125rem', fontWeight: 600, color: 'text.primary', mb: 0.5 }}>
            Account type
          </FormLabel>
          <Controller
            name="role"
            control={control}
            rules={{ required: 'Choose Customer or Merchant.' }}
            render={({ field }) => (
              <RadioGroup {...field} aria-label="Account type" aria-describedby={errors.role ? 'role-error' : undefined} sx={{ gap: 1 }}>
                {[
                  { value: 'CUSTOMER', title: 'Customer', description: 'Send money and manage your wallet.' },
                  { value: 'MERCHANT', title: 'Merchant', description: 'Accept payments and manage your wallet.' },
                ].map((option) => (
                  <FormControlLabel
                    key={option.value}
                    value={option.value}
                    control={<Radio size="small" sx={{ p: 0.75 }} />}
                    sx={{
                      m: 0,
                      py: 0.75,
                      px: 1.25,
                      border: '1px solid',
                      borderRadius: 1.5,
                      borderColor: field.value === option.value ? 'secondary.main' : 'divider',
                      bgcolor: field.value === option.value ? 'rgba(0, 121, 107, 0.04)' : 'background.paper',
                      transition: 'border-color 0.15s ease, background-color 0.15s ease',
                      alignItems: 'center',
                    }}
                    label={
                      <Box sx={{ ml: 0.25 }}>
                        <Typography component="span" variant="body2" sx={{ display: 'block', fontWeight: 600, lineHeight: 1.25 }}>
                          {option.title}
                        </Typography>
                        <Typography component="span" variant="caption" color="text.secondary" sx={{ display: 'block', lineHeight: 1.3 }}>
                          {option.description}
                        </Typography>
                      </Box>
                    }
                  />
                ))}
              </RadioGroup>
            )}
          />
          {errors.role && <FormHelperText id="role-error">{errors.role.message}</FormHelperText>}
        </FormControl>

        <Button
          type="submit"
          fullWidth
          variant="contained"
          size="large"
          disabled={isSubmitting}
          sx={{ py: 1.2 }}
          startIcon={isSubmitting ? <CircularProgress size={18} color="inherit" aria-hidden="true" /> : undefined}
        >
          {isSubmitting ? 'Creating account…' : 'Create account'}
        </Button>
      </Stack>
    </Box>
  );
};
