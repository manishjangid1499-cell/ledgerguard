import React from 'react';
import { Box, keyframes } from '@mui/material';
import { Outlet } from 'react-router-dom';
import { AuthBackground } from './AuthBackground';

const haloPulse = keyframes`
  0% {
    transform: translate(-50%, -50%) scale(0.98);
    opacity: 0.8;
  }
  50% {
    transform: translate(-50%, -50%) scale(1.02);
    opacity: 1;
  }
  100% {
    transform: translate(-50%, -50%) scale(0.98);
    opacity: 0.8;
  }
`;

export const AuthLayout: React.FC = () => {
  return (
    <Box sx={{ minHeight: '100dvh', position: 'relative', width: '100%', overflowX: 'hidden' }}>
      {/* Skip Link for Accessibility */}
      <a className="skip-link" href="#auth-main">Skip to authentication form</a>

      {/* Atmospheric Ambient Canvas */}
      <AuthBackground />

      {/* Centered Auth Stage */}
      <Box
        component="main"
        id="auth-main"
        tabIndex={-1}
        sx={{
          position: 'relative',
          zIndex: 1,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'center',
          alignItems: 'center',
          minHeight: '100dvh',
          p: { xs: 2.5, sm: 4, md: 5 },
          outline: 'none',
        }}
      >
        {/* Subtle Card Halo (Soft depth & light diffusion behind central form) */}
        <Box
          aria-hidden="true"
          sx={{
            position: 'absolute',
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            width: { xs: '90%', sm: 520, md: 560 },
            height: { xs: '85%', sm: 620, md: 680 },
            maxWidth: 580,
            maxHeight: 740,
            borderRadius: '28px',
            background:
              'radial-gradient(ellipse at center, rgba(0, 121, 107, 0.05) 0%, rgba(15, 41, 66, 0.025) 50%, rgba(248, 250, 252, 0) 72%)',
            filter: 'blur(36px)',
            pointerEvents: 'none',
            zIndex: 0,
            animation: `${haloPulse} 16s ease-in-out infinite alternate`,
            '@media (prefers-reduced-motion: reduce)': {
              animation: 'none !important',
            },
          }}
        />

        <Box sx={{ position: 'relative', zIndex: 1, width: '100%', display: 'flex', justifyContent: 'center' }}>
          <Outlet />
        </Box>
      </Box>
    </Box>
  );
};
