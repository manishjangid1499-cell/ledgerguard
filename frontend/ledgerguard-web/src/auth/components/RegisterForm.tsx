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
  RadioGroup,
  FormControlLabel,
  Radio,
  FormHelperText,
  Typography,
} from '@mui/material';
import Visibility from '@mui/icons-material/Visibility';
import VisibilityOff from '@mui/icons-material/VisibilityOff';
import PersonOutlinedIcon from '@mui/icons-material/PersonOutlined';
import StorefrontOutlinedIcon from '@mui/icons-material/StorefrontOutlined';
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
      <Stack spacing={1.5}>
        {serverError && (
          <Alert severity="error" onClose={() => setServerError(null)} sx={{ borderRadius: '10px', fontSize: '0.875rem' }}>
            {serverError}
          </Alert>
        )}

        <Box sx={{ textAlign: 'left' }}>
          <Typography
            component="label"
            htmlFor="fullName"
            sx={{
              display: 'block',
              fontSize: '0.8125rem',
              fontWeight: 600,
              color: 'text.primary',
              mb: 0.5,
            }}
          >
            Full name <Box component="span" sx={{ color: 'error.main' }}>*</Box>
          </Typography>
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
            type="text"
            autoComplete="name"
            hiddenLabel
            autoFocus
            fullWidth
            error={Boolean(errors.fullName)}
            helperText={errors.fullName?.message}
            disabled={isSubmitting}
            sx={{
              '& .MuiOutlinedInput-root': {
                borderRadius: '10px',
                bgcolor: '#ffffff',
              },
            }}
          />
        </Box>

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

        <Box sx={{ textAlign: 'left' }}>
          <Typography
            component="label"
            htmlFor="password"
            sx={{
              display: 'block',
              fontSize: '0.8125rem',
              fontWeight: 600,
              color: 'text.primary',
              mb: 0.5,
            }}
          >
            Password <Box component="span" sx={{ color: 'error.main' }}>*</Box>
          </Typography>
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
            type={showPassword ? 'text' : 'password'}
            autoComplete="new-password"
            hiddenLabel
            fullWidth
            error={Boolean(errors.password)}
            helperText={errors.password?.message}
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
            Confirm password <Box component="span" sx={{ color: 'error.main' }}>*</Box>
          </Typography>
          <TextField
            {...register('confirmPassword', {
              required: 'Confirm password is required.',
              validate: (val) => val === watch('password') || 'Passwords do not match.',
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
        </Box>

        <FormControl component="fieldset" error={Boolean(errors.role)} disabled={isSubmitting} fullWidth>
          <Typography
            component="legend"
            sx={{
              fontSize: '0.8125rem',
              fontWeight: 600,
              color: 'text.primary',
              mb: 0.5,
              textAlign: 'left',
              display: 'block',
            }}
          >
            Account type
          </Typography>
          <Controller
            name="role"
            control={control}
            rules={{ required: 'Choose Customer or Merchant.' }}
            render={({ field }) => (
              <RadioGroup
                {...field}
                aria-label="Account type"
                aria-describedby={errors.role ? 'role-error' : undefined}
              >
                <Box
                  sx={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 1fr',
                    gap: 1.25,
                  }}
                >
                  {[
                    {
                      value: 'CUSTOMER',
                      title: 'Customer',
                      subtitle: 'Personal',
                      icon: PersonOutlinedIcon,
                    },
                    {
                      value: 'MERCHANT',
                      title: 'Merchant',
                      subtitle: 'Business',
                      icon: StorefrontOutlinedIcon,
                    },
                  ].map((option) => {
                    const isSelected = field.value === option.value;
                    const IconComponent = option.icon;
                    return (
                      <FormControlLabel
                        key={option.value}
                        value={option.value}
                        control={
                          <Radio
                            size="small"
                            sx={{
                              position: 'absolute',
                              opacity: 0,
                              width: 0,
                              height: 0,
                              pointerEvents: 'none',
                            }}
                          />
                        }
                        sx={{
                          m: 0,
                          py: 1,
                          px: 1.25,
                          minHeight: 48,
                          borderRadius: '10px',
                          border: '1.5px solid',
                          borderColor: isSelected ? 'secondary.main' : 'rgba(15, 41, 66, 0.12)',
                          bgcolor: isSelected ? 'rgba(0, 121, 107, 0.04)' : '#ffffff',
                          boxShadow: isSelected
                            ? '0 0 0 1px #00796b, 0 1px 3px rgba(0, 121, 107, 0.06)'
                            : 'none',
                          transition: 'all 0.15s ease',
                          cursor: 'pointer',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'flex-start',
                          '&:hover': {
                            borderColor: isSelected ? 'secondary.main' : '#94a3b8',
                            bgcolor: isSelected ? 'rgba(0, 121, 107, 0.06)' : 'rgba(15, 41, 66, 0.02)',
                          },
                        }}
                        label={
                          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', width: '100%' }}>
                            <Box
                              sx={{
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                width: 28,
                                height: 28,
                                borderRadius: '6px',
                                bgcolor: isSelected
                                  ? 'rgba(0, 121, 107, 0.12)'
                                  : 'rgba(15, 41, 66, 0.05)',
                                color: isSelected ? 'secondary.main' : 'text.secondary',
                                flexShrink: 0,
                              }}
                            >
                              <IconComponent sx={{ fontSize: 17 }} />
                            </Box>
                            <Box sx={{ flex: 1, minWidth: 0 }}>
                              <Typography
                                component="span"
                                variant="body2"
                                sx={{
                                  display: 'block',
                                  fontWeight: 600,
                                  lineHeight: 1.2,
                                  color: isSelected ? 'primary.main' : 'text.primary',
                                  fontSize: '0.8125rem',
                                }}
                              >
                                {option.title}
                              </Typography>
                              <Typography
                                component="span"
                                variant="caption"
                                sx={{
                                  display: 'block',
                                  color: 'text.secondary',
                                  lineHeight: 1.2,
                                  fontSize: '0.6875rem',
                                }}
                              >
                                {option.subtitle}
                              </Typography>
                            </Box>
                            <Box
                              sx={{
                                width: 14,
                                height: 14,
                                borderRadius: '50%',
                                border: '1.5px solid',
                                borderColor: isSelected ? 'secondary.main' : 'rgba(15, 41, 66, 0.25)',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                flexShrink: 0,
                              }}
                            >
                              {isSelected && (
                                <Box
                                  sx={{
                                    width: 6,
                                    height: 6,
                                    borderRadius: '50%',
                                    bgcolor: 'secondary.main',
                                  }}
                                />
                              )}
                            </Box>
                          </Stack>
                        }
                      />
                    );
                  })}
                </Box>
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
          startIcon={isSubmitting ? <CircularProgress size={18} color="inherit" aria-hidden="true" /> : undefined}
        >
          {isSubmitting ? 'Creating account…' : 'Create account'}
        </Button>
      </Stack>
    </Box>
  );
};
