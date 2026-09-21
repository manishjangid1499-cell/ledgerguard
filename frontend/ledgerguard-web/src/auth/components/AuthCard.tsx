import { ReactNode } from 'react';
import { Box, Link, Typography } from '@mui/material';
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

export const AuthCard = ({
  title,
  description,
  children,
  alternateText,
  alternateLabel,
  alternateRoute,
}: AuthCardProps) => (
  <Box sx={{ width: '100%', maxWidth: 420 }}>
    {/* Mobile-only Brand Logo (Left-aligned above the title, hidden on desktop) */}
    <Box sx={{ mb: 3.5, display: { xs: 'block', md: 'none' } }}>
      <RouterLink to="/" aria-label="LedgerGuard home" style={{ textDecoration: 'none', display: 'inline-flex' }}>
        <BrandLogo size="small" />
      </RouterLink>
    </Box>

    <Box sx={{ mb: 3 }}>
      <Typography
        variant="h4"
        component="h1"
        sx={{
          fontWeight: 700,
          mb: 0.75,
          color: 'primary.main',
          fontSize: { xs: '1.5rem', sm: '1.75rem' },
          letterSpacing: '-0.02em',
        }}
      >
        {title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.5, fontSize: '0.9375rem' }}>
        {description}
      </Typography>
    </Box>

    {children}

    <Typography variant="body2" color="text.secondary" sx={{ mt: 3, textAlign: 'center', lineHeight: 1.5 }}>
      {alternateText}{' '}
      <Link
        component={RouterLink}
        to={alternateRoute}
        underline="hover"
        sx={{ fontWeight: 600, color: 'secondary.main' }}
      >
        {alternateLabel}
      </Link>
    </Typography>
  </Box>
);
