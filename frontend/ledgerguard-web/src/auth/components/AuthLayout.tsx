import React from 'react';
import { Box, Stack, Typography } from '@mui/material';
import { Link as RouterLink, Outlet } from 'react-router-dom';
import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined';
import { BrandLogo } from '../../shared/components/BrandLogo';

const trustPoints = [
  'Traceable balances',
  'Protected repeat requests',
  'Controlled settlement',
];

export const AuthLayout: React.FC = () => {
  return (
    <Box sx={{ minHeight: '100dvh', display: 'flex', width: '100%' }}>
      {/* Skip Link for Accessibility */}
      <a className="skip-link" href="#auth-main">Skip to authentication form</a>

      {/* Desktop Left Brand Panel (Hidden below md / 900px) */}
      <Box
        component="aside"
        aria-label="Brand Overview"
        sx={{
          display: { xs: 'none', md: 'flex' },
          flexDirection: 'column',
          justifyContent: 'space-between',
          width: { md: '40%', lg: '38%' },
          minWidth: 360,
          background: 'radial-gradient(ellipse at 20% 15%, #163e65 0%, #0a1f33 55%, #051321 100%)',
          bgcolor: '#08192b',
          p: { md: 5, lg: 6 },
          color: '#ffffff',
          position: 'relative',
          overflow: 'hidden',
          borderRight: '1px solid',
          borderColor: 'rgba(255, 255, 255, 0.08)',
        }}
      >
        {/* Top: Brand Logo */}
        <Box>
          <RouterLink to="/" aria-label="LedgerGuard home" style={{ textDecoration: 'none', display: 'inline-flex' }}>
            <BrandLogo size="medium" contrast="light" subtitle={false} />
          </RouterLink>
        </Box>

        {/* Middle: Brand Headline & Trust Points */}
        <Box sx={{ my: 'auto', py: 4, maxWidth: 390 }}>
          <Typography
            variant="h4"
            component="h2"
            sx={{
              color: '#ffffff',
              fontWeight: 700,
              lineHeight: 1.22,
              letterSpacing: '-0.025em',
              mb: 2,
              fontSize: { md: '1.75rem', lg: '2rem' },
            }}
          >
            Financial operations with a clear record.
          </Typography>
          <Typography
            variant="body1"
            sx={{
              color: 'rgba(255, 255, 255, 0.72)',
              lineHeight: 1.65,
              mb: 4,
              fontSize: '0.9375rem',
            }}
          >
            Move money through a platform designed to preserve traceable financial outcomes.
          </Typography>

          <Stack spacing={2}>
            {trustPoints.map((point) => (
              <Stack key={point} direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                <CheckCircleOutlinedIcon sx={{ fontSize: 18, color: '#26a69a', flexShrink: 0 }} />
                <Typography
                  variant="body2"
                  sx={{ color: 'rgba(255, 255, 255, 0.9)', fontWeight: 500, fontSize: '0.875rem' }}
                >
                  {point}
                </Typography>
              </Stack>
            ))}
          </Stack>
        </Box>

      </Box>

      {/* Right Auth Area (Full width on mobile, 60-62% on desktop) */}
      <Box
        component="main"
        id="auth-main"
        tabIndex={-1}
        sx={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
          bgcolor: 'background.paper',
          minHeight: '100dvh',
          p: { xs: 2.5, sm: 4, md: 5 },
          overflowY: 'auto',
          outline: 'none',
        }}
      >
        <Outlet />
      </Box>
    </Box>
  );
};
