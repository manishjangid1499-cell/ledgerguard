import { ReactNode } from 'react';
import { Box, Container, Link, Paper, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { BrandLogo } from '../../shared/components/BrandLogo';

interface AuthCardProps {
  title: string;
  description: string;
  children: ReactNode;
  alternateText: string;
  alternateLabel: string;
  alternateRoute: '/login' | '/register';
}

export const AuthCard = ({ title, description, children, alternateText, alternateLabel, alternateRoute }: AuthCardProps) => (
  <Container maxWidth="xs">
    <Paper elevation={0} sx={{ p: { xs: 2.5, sm: 4 }, border: '1px solid', borderColor: 'divider', borderRadius: 2,
      boxShadow: '0 2px 8px rgba(15, 41, 66, 0.03)' }}>
      <Box sx={{ mb: 3, textAlign: 'center' }}>
        <Box sx={{ display: 'inline-flex', mb: 3 }}><BrandLogo size="medium" /></Box>
        <Typography variant="h5" component="h1" gutterBottom sx={{ fontWeight: 700 }}>{title}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.6 }}>{description}</Typography>
      </Box>
      {children}
      <Typography variant="body2" color="text.secondary" sx={{ mt: 3, textAlign: 'center', lineHeight: 1.8 }}>
        {alternateText}{' '}<Link component={RouterLink} to={alternateRoute} underline="hover" sx={{ fontWeight: 600 }}>{alternateLabel}</Link>
      </Typography>
    </Paper>
  </Container>
);
