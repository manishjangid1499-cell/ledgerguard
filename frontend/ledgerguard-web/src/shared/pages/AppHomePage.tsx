import React from 'react';
import {
  Container,
  Box,
  Typography,
  Paper,
  Stack,
  Chip,
  Divider,
  Card,
  CardContent,
  Grid,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty';
import SecurityIcon from '@mui/icons-material/Security';
import { useAuth } from '../../auth/hooks/useAuth';
import { UserRole } from '../types/user.types';

function formatRoleLabel(role: UserRole): string {
  switch (role) {
    case 'CUSTOMER':
      return 'Customer';
    case 'MERCHANT':
      return 'Merchant';
    case 'OPS':
      return 'Operations';
    default:
      return role;
  }
}

export const AppHomePage: React.FC = () => {
  const { user } = useAuth();

  return (
    <Container maxWidth="lg">
      <Paper
        elevation={0}
        sx={{
          p: { xs: 3, md: 4 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          bgcolor: 'background.paper',
          mb: 4,
        }}
      >
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' }, mb: 2 }}
        >
          <Box>
            <Typography variant="h5" component="h1" sx={{ fontWeight: 700, color: 'primary.main' }}>
              Welcome back
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Authenticated as {user?.email}
            </Typography>
          </Box>
          <Stack direction="row" spacing={1}>
            {user && (
              <Chip
                label={formatRoleLabel(user.role)}
                color={user.role === 'MERCHANT' ? 'secondary' : user.role === 'OPS' ? 'info' : 'primary'}
                variant="outlined"
                sx={{ fontWeight: 600 }}
              />
            )}
            <Chip
              icon={<CheckCircleIcon />}
              label="Active Session"
              color="success"
              variant="outlined"
              size="small"
              sx={{ fontWeight: 600 }}
            />
          </Stack>
        </Stack>

        <Divider sx={{ my: 2.5 }} />

        <Box sx={{ mb: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1 }}>
            Security & Identity Status
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ lineHeight: 1.6 }}>
            Your session is secured using short-lived HS256 JWT access tokens and single-use refresh token rotation.
            Access tokens are stored only in browser memory, while refresh tokens are protected in an HttpOnly cookie and
            rotated after use.
          </Typography>
        </Box>
      </Paper>

      <Typography variant="h6" sx={{ fontWeight: 600, mb: 2 }}>
        Platform Modules Roadmap
      </Typography>

      <Grid container spacing={3}>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card>
            <CardContent sx={{ p: 3 }}>
              <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 1.5 }}>
                <SecurityIcon color="primary" fontSize="small" />
                <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                  Identity & Access (Phase 4–5)
                </Typography>
                <Chip label="Active" color="success" size="small" variant="filled" />
              </Stack>
              <Typography variant="body2" color="text.secondary">
                User registration, credential authentication, stateless Bearer token authorization, and HttpOnly session
                restoration are fully active.
              </Typography>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, md: 6 }}>
          <Card sx={{ bgcolor: 'background.default' }}>
            <CardContent sx={{ p: 3 }}>
              <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 1.5 }}>
                <HourglassEmptyIcon color="disabled" fontSize="small" />
                <Typography variant="subtitle1" sx={{ fontWeight: 600, color: 'text.secondary' }}>
                  Financial Ledger Core (Phase 6+)
                </Typography>
                <Chip label="Coming Soon" size="small" variant="outlined" />
              </Stack>
              <Typography variant="body2" color="text.secondary">
                Double-entry accounts, journal transaction posting, balance holds, peer-to-peer transfers, and merchant
                payments will be introduced in subsequent phases.
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Container>
  );
};
