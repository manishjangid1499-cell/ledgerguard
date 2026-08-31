import React from 'react';
import {
  Container,
  Box,
  Typography,
  Paper,
  Stack,
  Chip,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings';
import { useAuth } from '../../auth/hooks/useAuth';
import { UserRole } from '../types/user.types';
import { WalletCard } from '../../wallet/components/WalletCard';
import { TransferForm } from '../../transfer/components/TransferForm';
import { RecentTransfersTable } from '../../transfer/components/RecentTransfersTable';

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
  const isFinancialUser = user?.role === 'CUSTOMER' || user?.role === 'MERCHANT';

  return (
    <Container maxWidth="lg" sx={{ py: { xs: 2, sm: 3 } }}>
      {/* Top Welcome Header */}
      <Paper
        elevation={0}
        sx={{
          p: { xs: 2.5, sm: 3 },
          border: '1px solid',
          borderColor: 'divider',
          borderRadius: 2,
          bgcolor: 'background.paper',
          mb: 3,
        }}
      >
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          sx={{ justifyContent: 'space-between', alignItems: { sm: 'center' } }}
        >
          <Box>
            <Typography variant="h5" component="h1" sx={{ fontWeight: 700, color: 'primary.main' }}>
              Financial Dashboard
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Logged in as {user?.email}
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
      </Paper>

      {/* Financial Experience for Customers & Merchants */}
      {isFinancialUser ? (
        <Stack spacing={3}>
          {/* Wallet Summary Card */}
          <WalletCard />

          {/* Transfer Creation Form */}
          <TransferForm />

          {/* Recent Transfers & Journal History */}
          <RecentTransfersTable />
        </Stack>
      ) : (
        /* Ops Administrative Landing */
        <Paper
          elevation={0}
          sx={{
            p: 4,
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 2,
            bgcolor: 'background.paper',
            textAlign: 'center',
          }}
        >
          <AdminPanelSettingsIcon sx={{ fontSize: 48, color: 'info.main', mb: 1.5 }} />
          <Typography variant="h6" sx={{ fontWeight: 700, mb: 1 }}>
            Operations & Administration Console
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 600, mx: 'auto', lineHeight: 1.6 }}>
            You are logged in with the <strong>OPS</strong> role. Operations accounts are privileged management
            identities and do not have customer deposit wallets or initiate end-user wallet transfers.
          </Typography>
        </Paper>
      )}
    </Container>
  );
};
