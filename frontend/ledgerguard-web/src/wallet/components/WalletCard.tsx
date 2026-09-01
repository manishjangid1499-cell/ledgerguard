import React, { useState } from 'react';
import {
  Card,
  CardContent,
  Typography,
  Stack,
  Box,
  IconButton,
  Tooltip,
  Chip,
  Skeleton,
  Alert,
  Grid,
} from '@mui/material';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';
import RefreshIcon from '@mui/icons-material/Refresh';
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import LockClockIcon from '@mui/icons-material/LockClock';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { walletApi } from '../api/walletApi';
import { formatMinorUnitsToInr } from '../../shared/utils/money';

export const WalletCard: React.FC = () => {
  const queryClient = useQueryClient();
  const [copied, setCopied] = useState(false);

  const {
    data: wallet,
    isLoading,
    isError,
    error,
    isFetching,
  } = useQuery({
    queryKey: ['wallet'],
    queryFn: walletApi.getMyWallet,
    staleTime: 30_000,
  });

  const handleCopyWalletId = async () => {
    if (!wallet?.ledgerAccountId) return;
    try {
      await navigator.clipboard.writeText(wallet.ledgerAccountId);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback
    }
  };

  const handleRefresh = () => {
    queryClient.invalidateQueries({ queryKey: ['wallet'] });
  };

  if (isLoading) {
    return (
      <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
        <CardContent sx={{ p: 3 }}>
          <Skeleton variant="text" width={140} height={28} />
          <Skeleton variant="text" width={220} height={56} sx={{ my: 1 }} />
          <Skeleton variant="rectangular" width="100%" height={40} sx={{ borderRadius: 1 }} />
        </CardContent>
      </Card>
    );
  }

  if (isError) {
    return (
      <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
        <CardContent sx={{ p: 3 }}>
          <Alert severity="error" action={
            <IconButton size="small" onClick={handleRefresh}>
              <RefreshIcon fontSize="small" />
            </IconButton>
          }>
            Failed to load wallet: {error instanceof Error ? error.message : 'Unknown error'}
          </Alert>
        </CardContent>
      </Card>
    );
  }

  if (!wallet) {
    return null;
  }

  const formattedPostedBalance = formatMinorUnitsToInr(wallet.balanceMinor);
  const formattedActiveHold = formatMinorUnitsToInr(wallet.activeHoldAmountMinor);
  const formattedAvailableBalance = formatMinorUnitsToInr(wallet.availableBalanceMinor);

  return (
    <Card
      elevation={0}
      sx={{
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 2,
        background: 'linear-gradient(135deg, rgba(25, 118, 210, 0.04) 0%, rgba(25, 118, 210, 0.01) 100%)',
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'flex-start', mb: 2.5 }}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 40,
                height: 40,
                borderRadius: 1.5,
                bgcolor: 'primary.main',
                color: 'primary.contrastText',
              }}
            >
              <AccountBalanceWalletIcon fontSize="small" />
            </Box>
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 600, color: 'text.secondary', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                Available Balance
              </Typography>
              <Typography variant="h4" component="div" sx={{ fontWeight: 800, color: 'text.primary', letterSpacing: -0.5 }}>
                {formattedAvailableBalance}
              </Typography>
            </Box>
          </Stack>

          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Chip
              label={wallet.status}
              color={wallet.status === 'ACTIVE' ? 'success' : 'default'}
              size="small"
              variant="outlined"
              sx={{ fontWeight: 600 }}
            />
            <Tooltip title="Refresh balance">
              <span>
                <IconButton size="small" onClick={handleRefresh} disabled={isFetching} aria-label="Refresh wallet balance">
                  <RefreshIcon fontSize="small" sx={{ animation: isFetching ? 'spin 1s linear infinite' : 'none' }} />
                </IconButton>
              </span>
            </Tooltip>
          </Stack>
        </Stack>

        <Grid container spacing={2} sx={{ mb: 2.5 }}>
          <Grid size={{ xs: 12, sm: 6 }}>
            <Box
              sx={{
                p: 1.5,
                bgcolor: 'background.paper',
                border: '1px solid',
                borderColor: 'divider',
                borderRadius: 1.5,
              }}
            >
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
                <CheckCircleIcon fontSize="small" color="action" />
                <Typography variant="caption" sx={{ fontWeight: 600, color: 'text.secondary', textTransform: 'uppercase' }}>
                  Posted Balance
                </Typography>
              </Stack>
              <Typography variant="h6" component="div" sx={{ fontWeight: 700, color: 'text.primary' }}>
                {formattedPostedBalance}
              </Typography>
            </Box>
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <Box
              sx={{
                p: 1.5,
                bgcolor: 'background.paper',
                border: '1px solid',
                borderColor: 'divider',
                borderRadius: 1.5,
              }}
            >
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
                <LockClockIcon fontSize="small" color="action" />
                <Typography variant="caption" sx={{ fontWeight: 600, color: 'text.secondary', textTransform: 'uppercase' }}>
                  On Hold
                </Typography>
              </Stack>
              <Typography variant="h6" component="div" sx={{ fontWeight: 700, color: 'text.secondary' }}>
                {formattedActiveHold}
              </Typography>
            </Box>
          </Grid>
        </Grid>

        <Box
          sx={{
            p: 1.5,
            bgcolor: 'background.paper',
            border: '1px solid',
            borderColor: 'divider',
            borderRadius: 1.5,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 1,
            flexWrap: 'wrap',
          }}
        >
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
              Wallet ID (Ledger Account)
            </Typography>
            <Typography
              variant="body2"
              sx={{
                fontFamily: 'monospace',
                fontWeight: 600,
                color: 'text.primary',
                wordBreak: 'break-all',
              }}
            >
              {wallet.ledgerAccountId}
            </Typography>
          </Box>

          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Tooltip title={copied ? 'Copied!' : 'Copy Wallet ID'}>
              <IconButton size="small" onClick={handleCopyWalletId} color={copied ? 'success' : 'default'}>
                {copied ? <CheckIcon fontSize="small" /> : <ContentCopyIcon fontSize="small" />}
              </IconButton>
            </Tooltip>
            <Chip
              label={wallet.accountType}
              size="small"
              color={wallet.accountType === 'MERCHANT' ? 'secondary' : 'primary'}
              variant="filled"
              sx={{ fontWeight: 600, fontSize: '0.75rem' }}
            />
            <Chip
              label={wallet.currency}
              size="small"
              variant="outlined"
              sx={{ fontWeight: 600, fontSize: '0.75rem' }}
            />
          </Stack>
        </Box>
      </CardContent>
    </Card>
  );
};
