import React from 'react';
import { Box, Stack, Typography } from '@mui/material';
import SecurityIcon from '@mui/icons-material/Security';

interface BrandLogoProps {
  size?: 'small' | 'medium' | 'large';
  subtitle?: boolean;
  contrast?: 'light' | 'dark';
}

export const BrandLogo: React.FC<BrandLogoProps> = ({
  size = 'medium',
  subtitle = true,
  contrast = 'dark',
}) => {
  const iconSize = size === 'small' ? 24 : size === 'large' ? 44 : 32;
  const titleVariant = size === 'small' ? 'h6' : size === 'large' ? 'h4' : 'h5';
  const isLight = contrast === 'light';

  return (
    <Stack direction="row" spacing={size === 'small' ? 1 : 1.5} sx={{ alignItems: 'center' }}>
      <SecurityIcon sx={{ fontSize: iconSize, color: isLight ? '#26a69a' : 'primary.main' }} />
      <Box>
        <Typography
          variant={titleVariant}
          component="span"
          sx={{
            fontWeight: 700,
            color: isLight ? '#ffffff' : 'primary.main',
            display: 'block',
            lineHeight: 1.1,
            ...(size === 'small' && { fontSize: '1.125rem' }),
          }}
        >
          LedgerGuard
        </Typography>
        {subtitle && size !== 'small' && (
          <Typography
            variant="caption"
            sx={{
              color: isLight ? 'rgba(255, 255, 255, 0.7)' : 'text.secondary',
              display: 'block',
              mt: 0.5,
              lineHeight: 1.5,
            }}
          >
            Payment Integrity & Ledger Platform
          </Typography>
        )}
      </Box>
    </Stack>
  );
};
