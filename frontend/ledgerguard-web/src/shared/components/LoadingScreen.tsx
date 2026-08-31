import React from 'react';
import { Box, CircularProgress, Typography, Stack } from '@mui/material';
import { BrandLogo } from './BrandLogo';

interface LoadingScreenProps {
  message?: string;
}

export const LoadingScreen: React.FC<LoadingScreenProps> = ({ message = 'Verifying security session...' }) => {
  return (
    <Box
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
        <CircularProgress size={36} thickness={4} color="primary" />
        <Typography variant="body2" color="text.secondary">
          {message}
        </Typography>
      </Stack>
    </Box>
  );
};
