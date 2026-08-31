import React from 'react';
import { Box, Stack, Typography } from '@mui/material';
import SecurityIcon from '@mui/icons-material/Security';

interface BrandLogoProps {
  size?: 'small' | 'medium' | 'large';
  subtitle?: boolean;
}

export const BrandLogo: React.FC<BrandLogoProps> = ({ size = 'medium', subtitle = true }) => {
  const iconSize = size === 'small' ? 24 : size === 'large' ? 44 : 32;
  const titleVariant = size === 'small' ? 'h6' : size === 'large' ? 'h4' : 'h5';

  return (
    <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
      <SecurityIcon color="primary" sx={{ fontSize: iconSize }} />
      <Box>
        <Typography
          variant={titleVariant}
          component="span"
          sx={{
            fontWeight: 700,
            color: 'primary.main',
            display: 'block',
            lineHeight: 1.1,
          }}
        >
          LedgerGuard
        </Typography>
        {subtitle && size !== 'small' && (
          <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mt: 0.2 }}>
            Payment Integrity & Ledger Platform
          </Typography>
        )}
      </Box>
    </Stack>
  );
};
