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

export const RegisterForm: React.FC = () => {
  const { register: registerUser } = useAuth();
  const navigate = useNavigate();
  const [showPassword, setShowPassword] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const submitting = useRef(false);

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
    if (submitting.current) return;
    submitting.current = true;
    setServerError(null);
    try {
      const createdUser = await registerUser(data);
      if (!createdUser || typeof createdUser.id !== 'string' || !createdUser.id
        || typeof createdUser.email !== 'string' || createdUser.role !== data.role) {
        setServerError('We could not confirm account creation. Try signing in with your email before creating another account.');
        return;
      }
      navigate('/login', {
        state: { message: 'Account created successfully. Sign in to continue.' },
      });
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.problem.errors && err.problem.errors.length > 0) {
          err.problem.errors.forEach((fieldErr) => {
            if (fieldErr.field === 'email' || fieldErr.field === 'password' || fieldErr.field === 'role') {
              const messages = {
                email: 'Enter a valid email address.',
                password: 'Use at least 12 characters and no more than 72 UTF-8 bytes.',
                role: 'Choose Customer or Merchant.',
              };
              setError(fieldErr.field, { message: messages[fieldErr.field] }, { shouldFocus: true });
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
            minLength: {
              value: 12,
              message: 'Password must be at least 12 characters',
            },
            validate: (val) => {
              if (!val.trim()) return 'Password is required';
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
          required
          fullWidth
          error={Boolean(errors.password)}
          helperText={errors.password?.message || 'At least 12 characters. Maximum 72 UTF-8 bytes; some characters use more than one byte.'}
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

        <FormControl component="fieldset" error={Boolean(errors.role)} disabled={isSubmitting}>
          <FormLabel component="legend" sx={{ fontSize: '0.875rem', fontWeight: 600, mb: 0.5 }}>
            Account type
          </FormLabel>
          <Controller
            name="role"
            control={control}
            rules={{ required: 'Account type is required' }}
            render={({ field }) => (
              <RadioGroup {...field} aria-label="Account type" aria-describedby={errors.role ? 'role-error' : undefined} sx={{ gap: 1 }}>
                {[
                  { value: 'CUSTOMER', title: 'Customer', description: 'Manage funds and send transfers.' },
                  { value: 'MERCHANT', title: 'Merchant', description: 'Receive payments and manage wallet funds.' },
                ].map((option) => (
                  <FormControlLabel key={option.value} value={option.value} control={<Radio size="small" />}
                    sx={{ m: 0, p: 1, border: '1px solid', borderRadius: 1,
                      borderColor: field.value === option.value ? 'secondary.main' : 'divider',
                      bgcolor: field.value === option.value ? 'rgba(0, 121, 107, 0.04)' : 'background.paper' }}
                    label={<Box>
                      <Typography component="span" variant="body2" sx={{ display: 'block', fontWeight: 600 }}>{option.title}</Typography>
                      <Typography component="span" variant="caption" color="text.secondary">{option.description}</Typography>
                    </Box>} />
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
          sx={{ py: 1.3 }}
          startIcon={isSubmitting ? <CircularProgress size={18} color="inherit" aria-hidden="true" /> : undefined}
        >
          {isSubmitting ? 'Creating account…' : 'Create account'}
        </Button>
      </Stack>
    </Box>
  );
};
