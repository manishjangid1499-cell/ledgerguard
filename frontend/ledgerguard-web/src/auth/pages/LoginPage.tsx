import React from 'react';
import { Container, Paper, Typography, Box, Link, Alert } from '@mui/material';
import { Link as RouterLink, useLocation } from 'react-router-dom';
import { LoginForm } from '../components/LoginForm';
import { BrandLogo } from '../../shared/components/BrandLogo';

export const LoginPage: React.FC = () => {
  const location = useLocation();
  const successMessage = (location.state as { message?: string } | null)?.message;

  return (
    <Container maxWidth="xs">
      <Paper
        elevation={0}
        sx={{
          p: { xs: 3, sm: 4 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          bgcolor: 'background.paper',
        }}
      >
        <Box sx={{ mb: 3, textAlign: 'center' }}>
          <Box sx={{ display: 'inline-flex', mb: 2 }}>
            <BrandLogo size="medium" />
          </Box>
          <Typography variant="h5" component="h1" gutterBottom sx={{ fontWeight: 700 }}>
            Sign in to LedgerGuard
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Enter your credentials to access your account.
          </Typography>
        </Box>

        {successMessage && (
          <Alert severity="success" sx={{ mb: 2.5 }}>
            {successMessage}
          </Alert>
        )}

        <LoginForm />

        <Box sx={{ mt: 3, textAlign: 'center' }}>
          <Typography variant="body2" color="text.secondary">
            Don't have an account?{' '}
            <Link component={RouterLink} to="/register" sx={{ fontWeight: 600, textDecoration: 'none' }}>
              Create account
            </Link>
          </Typography>
        </Box>
      </Paper>
    </Container>
  );
};
