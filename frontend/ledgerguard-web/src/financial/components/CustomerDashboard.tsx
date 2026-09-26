import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Grid,
  IconButton,
  Paper,
  Skeleton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import AccountBalanceWalletOutlinedIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import HourglassEmptyOutlinedIcon from '@mui/icons-material/HourglassEmptyOutlined';
import ReceiptLongOutlinedIcon from '@mui/icons-material/ReceiptLongOutlined';
import RefreshIcon from '@mui/icons-material/Refresh';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import AddCardIcon from '@mui/icons-material/AddCard';
import SendIcon from '@mui/icons-material/Send';
import PaymentsIcon from '@mui/icons-material/Payments';
import ArrowDownwardOutlinedIcon from '@mui/icons-material/ArrowDownwardOutlined';
import CallMadeIcon from '@mui/icons-material/CallMade';
import CallReceivedIcon from '@mui/icons-material/CallReceived';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { walletApi } from '../../wallet/api/walletApi';
import { transferApi } from '../../transfer/api/transferApi';
import { TransferSummary } from '../../transfer/types/transfer.types';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { CopyButton } from '../../shared/components/CopyButton';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';
import { getErrorMessage } from '../../shared/api/errorMessage';

export const CustomerDashboard = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [lastRefreshed, setLastRefreshed] = useState<Date>(new Date());

  const walletQuery = useQuery({
    queryKey: ['wallet'],
    queryFn: walletApi.getMyWallet,
    staleTime: 30_000,
  });

  const recentTransfersQuery = useQuery({
    queryKey: ['transfers', 'recent'],
    queryFn: () => transferApi.getTransfers(0, 5),
    staleTime: 15_000,
  });

  const isRefreshing = walletQuery.isFetching || recentTransfersQuery.isFetching;

  const handleRefresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['wallet'] }),
      queryClient.invalidateQueries({ queryKey: ['transfers', 'recent'] }),
    ]);
    setLastRefreshed(new Date());
  };

  const wallet = walletQuery.data;
  const isWalletInactive = Boolean(wallet && wallet.status && wallet.status !== 'ACTIVE');
  const recentTransfers = recentTransfersQuery.data?.items ?? [];

  return (
    <Stack spacing={3}>
      {/* Page Header */}
      <Box
        sx={{
          display: 'flex',
          flexDirection: { xs: 'column', sm: 'row' },
          justifyContent: 'space-between',
          alignItems: { xs: 'flex-start', sm: 'center' },
          gap: 2,
        }}
      >
        <Box>
          <Typography
            variant="h4"
            component="h1"
            color="primary.main"
            sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, fontWeight: 700, mb: 0.5 }}
          >
            Your wallet
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Add money, send funds, pay merchants and review your wallet activity.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', alignSelf: { xs: 'stretch', sm: 'auto' } }}>
          <Tooltip
            title={`Refresh dashboard (last updated ${lastRefreshed.toLocaleTimeString([], {
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit',
            })})`}
          >
            <span>
              <IconButton
                aria-label="Refresh wallet and activity"
                onClick={handleRefresh}
                disabled={isRefreshing}
                size="medium"
                sx={{ border: '1px solid', borderColor: 'divider' }}
              >
                {isRefreshing ? <CircularProgress size={20} /> : <RefreshIcon fontSize="small" />}
              </IconButton>
            </span>
          </Tooltip>
        </Stack>
      </Box>

      {/* Wallet Error Alert */}
      {walletQuery.isError && (
        <Alert
          severity="error"
          action={
            <Button
              color="inherit"
              size="small"
              onClick={() => {
                void walletQuery.refetch();
              }}
            >
              Retry
            </Button>
          }
        >
          {getErrorMessage(walletQuery.error, 'Unable to load wallet balances.')}
        </Alert>
      )}

      {/* Wallet Summary Grid */}
      <Box>
        <Typography
          component="h2"
          variant="subtitle2"
          color="text.secondary"
          sx={{ textTransform: 'uppercase', letterSpacing: 0.5, fontSize: '0.75rem', mb: 1.5, fontWeight: 700 }}
        >
          Wallet balances
        </Typography>
        <Grid container spacing={2}>
          {/* Available Balance */}
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <Card sx={{ height: '100%', borderLeft: '4px solid', borderColor: 'primary.main' }}>
              <CardContent sx={{ p: 2.5 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, color: 'primary.main' }}>
                  <AccountBalanceWalletOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    Available balance
                  </Typography>
                </Stack>
                {walletQuery.isLoading ? (
                  <Skeleton variant="text" width="60%" height={40} />
                ) : (
                  <Typography
                    variant="h4"
                    sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: 'primary.main' }}
                  >
                    {formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  Immediately available to spend or withdraw
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          {/* On Hold */}
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent sx={{ p: 2.5 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, color: 'text.secondary' }}>
                  <HourglassEmptyOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    On hold
                  </Typography>
                </Stack>
                {walletQuery.isLoading ? (
                  <Skeleton variant="text" width="50%" height={40} />
                ) : (
                  <Typography variant="h5" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                    {formatMinorUnitsToInr(wallet?.activeHoldAmountMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  Reserved during in-flight operations
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          {/* Posted Balance */}
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent sx={{ p: 2.5 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, color: 'text.secondary' }}>
                  <ReceiptLongOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    Posted balance
                  </Typography>
                </Stack>
                {walletQuery.isLoading ? (
                  <Skeleton variant="text" width="50%" height={40} />
                ) : (
                  <Typography variant="h5" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                    {formatMinorUnitsToInr(wallet?.balanceMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  Total balance in double-entry ledger
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      </Box>

      {/* Wallet Identifier & Status Banner */}
      <Paper variant="outlined" sx={{ p: 2, bgcolor: 'background.default' }}>
        <Grid container spacing={2} sx={{ alignItems: 'center' }}>
          <Grid size={{ xs: 12, sm: 7 }}>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', fontWeight: 600 }}>
              YOUR WALLET ID
            </Typography>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mt: 0.5 }}>
              {walletQuery.isLoading ? (
                <Skeleton variant="text" width={280} height={28} />
              ) : (
                <>
                  <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600, overflowWrap: 'anywhere' }}>
                    {wallet?.ledgerAccountId ?? '—'}
                  </Typography>
                  {wallet?.ledgerAccountId && (
                    <CopyButton value={wallet.ledgerAccountId} label="wallet ID" />
                  )}
                </>
              )}
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, sm: 5 }} sx={{ display: 'flex', justifyContent: { xs: 'flex-start', sm: 'flex-end' }, alignItems: 'center', gap: 1.5 }}>
            <Typography variant="caption" color="text.secondary">
              Wallet status:
            </Typography>
            {walletQuery.isLoading ? (
              <Skeleton variant="rectangular" width={60} height={24} sx={{ borderRadius: 1 }} />
            ) : (
              <StatusBadge status={wallet?.status ?? 'ACTIVE'} />
            )}
          </Grid>
        </Grid>
      </Paper>

      {/* Quick Actions */}
      <Box>
        <Typography
          component="h2"
          variant="subtitle2"
          color="text.secondary"
          sx={{ textTransform: 'uppercase', letterSpacing: 0.5, fontSize: '0.75rem', mb: 1.5, fontWeight: 700 }}
        >
          Quick actions
        </Typography>

        {isWalletInactive && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Your wallet status is {wallet?.status}. Financial actions (Add money, Send money, Pay merchant, Withdraw funds) are disabled until your wallet is active.
          </Alert>
        )}

        <Grid container spacing={2}>
          <Grid size={{ xs: 6, md: 3 }}>
            <Card
              component={isWalletInactive ? 'button' : RouterLink}
              {...(!isWalletInactive
                ? { to: '/app/activity?type=funding' }
                : {
                    type: 'button',
                    disabled: true,
                    'aria-disabled': 'true',
                    'aria-label': `Add money (disabled: wallet is ${wallet?.status ?? 'inactive'})`,
                    onClick: (e: React.MouseEvent) => {
                      e.preventDefault();
                      e.stopPropagation();
                    },
                    onKeyDown: (e: React.KeyboardEvent) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        e.stopPropagation();
                      }
                    },
                  })}
              sx={{
                p: 2,
                height: '100%',
                width: '100%',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                textAlign: 'center',
                textDecoration: 'none',
                color: 'inherit',
                font: 'inherit',
                bgcolor: 'background.paper',
                border: '1px solid',
                borderColor: 'divider',
                opacity: isWalletInactive ? 0.6 : 1,
                cursor: isWalletInactive ? 'not-allowed' : 'pointer',
                transition: 'border-color 0.2s, transform 0.2s',
                ...(!isWalletInactive ? {
                  '&:hover': {
                    borderColor: 'primary.main',
                    transform: 'translateY(-2px)',
                  },
                } : {}),
              }}
            >
              <AddCardIcon color={isWalletInactive ? 'disabled' : 'primary'} sx={{ fontSize: 32, mb: 1 }} />
              <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
                Add money
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5 }}>
                Simulate funding via payment rail
              </Typography>
            </Card>
          </Grid>

          <Grid size={{ xs: 6, md: 3 }}>
            <Card
              component={isWalletInactive ? 'button' : RouterLink}
              {...(!isWalletInactive
                ? { to: '/app/activity?type=transfers' }
                : {
                    type: 'button',
                    disabled: true,
                    'aria-disabled': 'true',
                    'aria-label': `Send money (disabled: wallet is ${wallet?.status ?? 'inactive'})`,
                    onClick: (e: React.MouseEvent) => {
                      e.preventDefault();
                      e.stopPropagation();
                    },
                    onKeyDown: (e: React.KeyboardEvent) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        e.stopPropagation();
                      }
                    },
                  })}
              sx={{
                p: 2,
                height: '100%',
                width: '100%',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                textAlign: 'center',
                textDecoration: 'none',
                color: 'inherit',
                font: 'inherit',
                bgcolor: 'background.paper',
                border: '1px solid',
                borderColor: 'divider',
                opacity: isWalletInactive ? 0.6 : 1,
                cursor: isWalletInactive ? 'not-allowed' : 'pointer',
                transition: 'border-color 0.2s, transform 0.2s',
                ...(!isWalletInactive ? {
                  '&:hover': {
                    borderColor: 'primary.main',
                    transform: 'translateY(-2px)',
                  },
                } : {}),
              }}
            >
              <SendIcon color={isWalletInactive ? 'disabled' : 'primary'} sx={{ fontSize: 32, mb: 1 }} />
              <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
                Send money
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5 }}>
                Direct peer-to-peer wallet transfer
              </Typography>
            </Card>
          </Grid>

          <Grid size={{ xs: 6, md: 3 }}>
            <Card
              component={isWalletInactive ? 'button' : RouterLink}
              {...(!isWalletInactive
                ? { to: '/app/activity?type=payments' }
                : {
                    type: 'button',
                    disabled: true,
                    'aria-disabled': 'true',
                    'aria-label': `Pay merchant (disabled: wallet is ${wallet?.status ?? 'inactive'})`,
                    onClick: (e: React.MouseEvent) => {
                      e.preventDefault();
                      e.stopPropagation();
                    },
                    onKeyDown: (e: React.KeyboardEvent) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        e.stopPropagation();
                      }
                    },
                  })}
              sx={{
                p: 2,
                height: '100%',
                width: '100%',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                textAlign: 'center',
                textDecoration: 'none',
                color: 'inherit',
                font: 'inherit',
                bgcolor: 'background.paper',
                border: '1px solid',
                borderColor: 'divider',
                opacity: isWalletInactive ? 0.6 : 1,
                cursor: isWalletInactive ? 'not-allowed' : 'pointer',
                transition: 'border-color 0.2s, transform 0.2s',
                ...(!isWalletInactive ? {
                  '&:hover': {
                    borderColor: 'primary.main',
                    transform: 'translateY(-2px)',
                  },
                } : {}),
              }}
            >
              <PaymentsIcon color={isWalletInactive ? 'disabled' : 'primary'} sx={{ fontSize: 32, mb: 1 }} />
              <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
                Pay merchant
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5 }}>
                Pay by merchant payment ID
              </Typography>
            </Card>
          </Grid>

          <Grid size={{ xs: 6, md: 3 }}>
            <Card
              component={isWalletInactive ? 'button' : RouterLink}
              {...(!isWalletInactive
                ? { to: '/app/activity?type=withdrawals' }
                : {
                    type: 'button',
                    disabled: true,
                    'aria-disabled': 'true',
                    'aria-label': `Withdraw funds (disabled: wallet is ${wallet?.status ?? 'inactive'})`,
                    onClick: (e: React.MouseEvent) => {
                      e.preventDefault();
                      e.stopPropagation();
                    },
                    onKeyDown: (e: React.KeyboardEvent) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        e.stopPropagation();
                      }
                    },
                  })}
              sx={{
                p: 2,
                height: '100%',
                width: '100%',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                textAlign: 'center',
                textDecoration: 'none',
                color: 'inherit',
                font: 'inherit',
                bgcolor: 'background.paper',
                border: '1px solid',
                borderColor: 'divider',
                opacity: isWalletInactive ? 0.6 : 1,
                cursor: isWalletInactive ? 'not-allowed' : 'pointer',
                transition: 'border-color 0.2s, transform 0.2s',
                ...(!isWalletInactive ? {
                  '&:hover': {
                    borderColor: 'primary.main',
                    transform: 'translateY(-2px)',
                  },
                } : {}),
              }}
            >
              <ArrowDownwardOutlinedIcon color={isWalletInactive ? 'disabled' : 'primary'} sx={{ fontSize: 32, mb: 1 }} />
              <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
                Withdraw funds
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5 }}>
                Move money through simulated payout provider
              </Typography>
            </Card>
          </Grid>
        </Grid>
      </Box>

      {/* Recent Transfers Table */}
      <Card>
        <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
          <Stack
            direction="row"
            spacing={1}
            sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2.5 }}
          >
            <Box>
              <Typography component="h2" variant="h6" sx={{ fontSize: '1.15rem', fontWeight: 600 }}>
                Recent transfers
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Your latest transfers sent and received.
              </Typography>
            </Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Tooltip title="Refresh recent transfers">
                <span>
                  <IconButton
                    aria-label="Refresh recent transfers"
                    size="small"
                    onClick={() => {
                      void recentTransfersQuery.refetch();
                    }}
                    disabled={recentTransfersQuery.isFetching}
                  >
                    <RefreshIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
              <Button
                component={RouterLink}
                to="/app/activity?type=transfers"
                endIcon={<ArrowForwardIcon fontSize="small" />}
                size="small"
                sx={{ fontWeight: 600 }}
              >
                View all transfers
              </Button>
            </Stack>
          </Stack>

          {recentTransfersQuery.isLoading ? (
            <Box sx={{ py: 2 }}>
              <Skeleton variant="rectangular" height={220} sx={{ borderRadius: 1 }} />
            </Box>
          ) : recentTransfersQuery.isError ? (
            <Alert
              severity="error"
              action={
                <Button
                  color="inherit"
                  size="small"
                  onClick={() => {
                    void recentTransfersQuery.refetch();
                  }}
                >
                  Retry
                </Button>
              }
            >
              {getErrorMessage(recentTransfersQuery.error, 'Unable to load recent activity.')}
            </Alert>
          ) : recentTransfers.length === 0 ? (
            <Box sx={{ py: 5, textAlign: 'center' }}>
              <Typography variant="body2" color="text.secondary" sx={{ fontWeight: 500 }}>
                No recent activity yet.
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                Transfers you send or receive will appear here.
              </Typography>
            </Box>
          ) : (
            <>
              {/* Mobile View: Cards */}
              <Box sx={{ display: { xs: 'block', md: 'none' } }}>
                {recentTransfers.map((transfer: TransferSummary) => {
                  const outgoing = transfer.direction === 'OUTGOING';
                  const counterparty = outgoing
                    ? transfer.destinationLedgerAccountId
                    : transfer.sourceLedgerAccountId;

                  return (
                    <Box key={transfer.transferId} sx={{ py: 2, borderTop: '1px solid', borderColor: 'divider' }}>
                      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 0.5 }}>
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                          {outgoing ? (
                            <CallMadeIcon fontSize="small" color="primary" />
                          ) : (
                            <CallReceivedIcon fontSize="small" color="secondary" />
                          )}
                          <Typography
                            variant="body2"
                            sx={{
                              fontWeight: 700,
                              fontVariantNumeric: 'tabular-nums',
                              color: outgoing ? 'text.primary' : 'secondary.main',
                            }}
                          >
                            {outgoing ? '− ' : '+ '}
                            {formatMinorUnitsToInr(transfer.amountMinor)}
                          </Typography>
                        </Stack>
                        <StatusBadge status="COMPLETED" />
                      </Stack>
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                        {formatDateTime(transfer.createdAt)}
                      </Typography>
                      <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', my: 0.5 }}>
                        <Typography
                          variant="caption"
                          color="text.secondary"
                          sx={{ fontFamily: 'monospace', overflowWrap: 'anywhere', flex: 1 }}
                        >
                          {outgoing ? 'To' : 'From'} {counterparty}
                        </Typography>
                        <CopyButton value={counterparty} label="counterparty wallet ID" />
                      </Stack>
                      <Stack direction="row" sx={{ justifyContent: 'flex-end', mt: 1 }}>
                        <Button
                          component={RouterLink}
                          to={`/app/transfers/${transfer.transferId}`}
                          size="small"
                          aria-label={`View transfer ${transfer.transferId}`}
                        >
                          Details
                        </Button>
                      </Stack>
                    </Box>
                  );
                })}
              </Box>

              {/* Desktop View: Table */}
              <TableContainer
                tabIndex={0}
                role="region"
                aria-label="Recent activity table"
                sx={{ display: { xs: 'none', md: 'block' } }}
              >
                <Table aria-label="Recent activity" size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Direction</TableCell>
                      <TableCell align="right">Amount</TableCell>
                      <TableCell>Counterparty wallet</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Date</TableCell>
                      <TableCell align="right">Details</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {recentTransfers.map((transfer: TransferSummary) => {
                      const outgoing = transfer.direction === 'OUTGOING';
                      const counterparty = outgoing
                        ? transfer.destinationLedgerAccountId
                        : transfer.sourceLedgerAccountId;

                      return (
                        <TableRow
                          key={transfer.transferId}
                          hover
                          onClick={() => navigate(`/app/transfers/${transfer.transferId}`)}
                          sx={{ cursor: 'pointer' }}
                        >
                          <TableCell>
                            <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                              {outgoing ? (
                                <CallMadeIcon fontSize="small" color="primary" />
                              ) : (
                                <CallReceivedIcon fontSize="small" color="secondary" />
                              )}
                              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                                {outgoing ? 'Sent' : 'Received'}
                              </Typography>
                            </Stack>
                          </TableCell>
                          <TableCell
                            align="right"
                            sx={{
                              fontWeight: 700,
                              fontVariantNumeric: 'tabular-nums',
                              whiteSpace: 'nowrap',
                              color: outgoing ? 'text.primary' : 'secondary.main',
                            }}
                          >
                            {outgoing ? '− ' : '+ '}
                            {formatMinorUnitsToInr(transfer.amountMinor)}
                          </TableCell>
                          <TableCell>
                            <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                              <Typography
                                variant="caption"
                                sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}
                              >
                                {counterparty.slice(0, 8)}…{counterparty.slice(-4)}
                              </Typography>
                              <CopyButton value={counterparty} label="counterparty wallet ID" />
                            </Stack>
                          </TableCell>
                          <TableCell>
                            <StatusBadge status="COMPLETED" />
                          </TableCell>
                          <TableCell sx={{ color: 'text.secondary', fontSize: '0.8rem', whiteSpace: 'nowrap' }}>
                            {formatDateTime(transfer.createdAt)}
                          </TableCell>
                          <TableCell align="right">
                            <IconButton
                              component={RouterLink}
                              to={`/app/transfers/${transfer.transferId}`}
                              aria-label={`View transfer ${transfer.transferId}`}
                              onClick={e => e.stopPropagation()}
                              size="small"
                            >
                              <ArrowForwardIcon fontSize="small" />
                            </IconButton>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            </>
          )}
        </CardContent>
      </Card>
    </Stack>
  );
};
