import React from 'react';
import { Box, CircularProgress, Typography, Stack } from '@mui/material';
import { BrandLogo } from './BrandLogo';

interface LoadingScreenProps {
  message?: string;
}

export const LoadingScreen: React.FC<LoadingScreenProps> = ({ message = 'Restoring your session…' }) => {
  return (
    <Box
      role="status"
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: 'background.default',
        p: 3,
      }}
    >
      <Stack spacing={3} sx={{ alignItems: 'center', textAlign: 'center' }}>
        <BrandLogo size="large" />
        <CircularProgress size={28} thickness={4} color="primary" aria-hidden="true" />
        <Typography variant="body2" color="text.secondary">
          {message}
        </Typography>
      </Stack>
    </Box>
  );
};
