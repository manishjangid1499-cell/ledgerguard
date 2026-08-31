import React from 'react';
import { Container, Paper, Typography, Box, Link } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { RegisterForm } from '../components/RegisterForm';
import { BrandLogo } from '../../shared/components/BrandLogo';

export const RegisterPage: React.FC = () => {
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
            Create an account
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Join the LedgerGuard financial ledger platform.
          </Typography>
        </Box>

        <RegisterForm />

        <Box sx={{ mt: 3, textAlign: 'center' }}>
          <Typography variant="body2" color="text.secondary">
            Already have an account?{' '}
            <Link component={RouterLink} to="/login" sx={{ fontWeight: 600, textDecoration: 'none' }}>
              Sign in
            </Link>
          </Typography>
        </Box>
      </Paper>
    </Container>
  );
};
