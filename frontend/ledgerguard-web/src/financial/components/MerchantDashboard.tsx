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
import RefreshIcon from '@mui/icons-material/Refresh';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import PaymentsOutlinedIcon from '@mui/icons-material/PaymentsOutlined';
import ReceiptLongOutlinedIcon from '@mui/icons-material/ReceiptLongOutlined';
import TrendingUpOutlinedIcon from '@mui/icons-material/TrendingUpOutlined';
import ArrowDownwardOutlinedIcon from '@mui/icons-material/ArrowDownwardOutlined';
import HourglassEmptyOutlinedIcon from '@mui/icons-material/HourglassEmptyOutlined';
import { Link as RouterLink } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { walletApi } from '../../wallet/api/walletApi';
import { financialApi } from '../api';
import { Payment } from '../types';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { CopyButton } from '../../shared/components/CopyButton';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';
import { getErrorMessage } from '../../shared/api/errorMessage';

export const MerchantDashboard = () => {
  const queryClient = useQueryClient();
  const [lastRefreshed, setLastRefreshed] = useState<Date>(new Date());

  const walletQuery = useQuery({
    queryKey: ['wallet'],
    queryFn: walletApi.getMyWallet,
    staleTime: 30_000,
  });

  const summaryQuery = useQuery({
    queryKey: ['merchantSummary'],
    queryFn: financialApi.merchantSummary,
    staleTime: 30_000,
  });

  const recentPaymentsQuery = useQuery({
    queryKey: ['payments', 'recent'],
    queryFn: () => financialApi.payments({ page: 0, size: 5, sort: 'newest' }),
    staleTime: 15_000,
  });

  const isRefreshing = walletQuery.isFetching || summaryQuery.isFetching || recentPaymentsQuery.isFetching;

  const handleRefresh = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['wallet'] }),
      queryClient.invalidateQueries({ queryKey: ['merchantSummary'] }),
      queryClient.invalidateQueries({ queryKey: ['payments', 'recent'] }),
    ]);
    setLastRefreshed(new Date());
  };

  const wallet = walletQuery.data;
  const summary = summaryQuery.data;
  const recentPayments = recentPaymentsQuery.data?.items ?? [];

  return (
    <Stack spacing={3}>
      {/* A. Compact Page Header */}
      <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, justifyContent: 'space-between', alignItems: { xs: 'flex-start', sm: 'center' }, gap: 2 }}>
        <Box>
          <Typography variant="h4" component="h1" color="primary.main" sx={{ fontSize: { xs: '1.75rem', md: '2rem' }, fontWeight: 700, mb: 0.5 }}>
            Merchant overview
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Track customer payments, review fees and refunds, and withdraw available funds.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', alignSelf: { xs: 'stretch', sm: 'auto' } }}>
          <Tooltip title={`Refresh dashboard (last updated ${lastRefreshed.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })})`}>
            <span>
              <IconButton
                aria-label="Refresh wallet and summary"
                onClick={handleRefresh}
                disabled={isRefreshing}
                size="medium"
                sx={{ border: '1px solid', borderColor: 'divider' }}
              >
                {isRefreshing ? <CircularProgress size={20} /> : <RefreshIcon fontSize="small" />}
              </IconButton>
            </span>
          </Tooltip>
          <Button
            component={RouterLink}
            to="/app/activity?type=payouts"
            variant="contained"
            color="primary"
            sx={{ fontWeight: 600, px: 2.5, whiteSpace: 'nowrap' }}
          >
            Withdraw funds
          </Button>
        </Stack>
      </Box>

      {/* Errors if any */}
      {walletQuery.isError && (
        <Alert severity="error" action={<Button color="inherit" size="small" onClick={() => { void walletQuery.refetch(); }}>Retry</Button>}>
          {getErrorMessage(walletQuery.error, 'Unable to load wallet balances.')}
        </Alert>
      )}
      {summaryQuery.isError && (
        <Alert severity="error" action={<Button color="inherit" size="small" onClick={() => { void summaryQuery.refetch(); }}>Retry</Button>}>
          {getErrorMessage(summaryQuery.error, 'Unable to load merchant summary KPIs.')}
        </Alert>
      )}

      {/* B. Authoritative Summary Grid */}
      <Box>
        <Typography component="h2" variant="subtitle2" color="text.secondary" sx={{ textTransform: 'uppercase', letterSpacing: 0.5, fontSize: '0.75rem', mb: 1.5, fontWeight: 700 }}>
          Financial Summary
        </Typography>
        <Grid container spacing={2}>
          {/* Card 1: Available Balance */}
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <Card sx={{ height: '100%', borderLeft: '4px solid', borderColor: 'primary.main' }}>
              <CardContent sx={{ p: 2.5 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, color: 'primary.main' }}>
                  <AccountBalanceWalletOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>Available balance</Typography>
                </Stack>
                {walletQuery.isLoading ? (
                  <Skeleton variant="text" width="60%" height={40} />
                ) : (
                  <Typography variant="h4" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: 'primary.main' }}>
                    {formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  Immediately available for withdrawal
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          {/* Card 2: On Hold */}
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent sx={{ p: 2.5 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, color: 'text.secondary' }}>
                  <HourglassEmptyOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>On hold</Typography>
                </Stack>
                {walletQuery.isLoading ? (
                  <Skeleton variant="text" width="50%" height={40} />
                ) : (
                  <Typography variant="h5" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                    {formatMinorUnitsToInr(wallet?.activeHoldAmountMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  Reserved during in-flight withdrawals
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          {/* Card 3: Posted Balance */}
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent sx={{ p: 2.5 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, color: 'text.secondary' }}>
                  <ReceiptLongOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>Posted balance</Typography>
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

          {/* Card 4: Gross Received */}
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent sx={{ p: 2 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.75, color: 'text.secondary' }}>
                  <PaymentsOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>Gross received</Typography>
                </Stack>
                {summaryQuery.isLoading ? (
                  <Skeleton variant="text" width="60%" height={32} />
                ) : (
                  <Typography variant="h6" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                    {formatMinorUnitsToInr(summary?.grossReceivedMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  All-time customer payments
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          {/* Card 5: Net Received */}
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent sx={{ p: 2 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.75, color: 'secondary.main' }}>
                  <TrendingUpOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>Original net credited</Typography>
                </Stack>
                {summaryQuery.isLoading ? (
                  <Skeleton variant="text" width="60%" height={32} />
                ) : (
                  <Typography variant="h6" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: 'secondary.main' }}>
                    {formatMinorUnitsToInr(summary?.netReceivedMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  Gross minus fees (before refunds)
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          {/* Card 6: Platform Fees */}
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent sx={{ p: 2 }}>
                <Typography variant="body2" sx={{ fontWeight: 600, color: 'text.secondary', mb: 0.75 }}>
                  Platform fees
                </Typography>
                {summaryQuery.isLoading ? (
                  <Skeleton variant="text" width="50%" height={32} />
                ) : (
                  <Typography variant="h6" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                    {formatMinorUnitsToInr(summary?.platformFeesMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  Fees charged on completed payments
                </Typography>
              </CardContent>
            </Card>
          </Grid>

          {/* Card 7: Customer Refunds */}
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent sx={{ p: 2 }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.75, color: 'text.secondary' }}>
                  <ArrowDownwardOutlinedIcon fontSize="small" />
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>Customer refunds</Typography>
                </Stack>
                {summaryQuery.isLoading ? (
                  <Skeleton variant="text" width="50%" height={32} />
                ) : (
                  <Typography variant="h6" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                    {formatMinorUnitsToInr(summary?.refundedAmountMinor ?? '0')}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  Gross amount returned to customers
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      </Box>

      {/* C. Wallet Identifier Section */}
      <Card variant="outlined">
        <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
          <Typography component="h2" variant="h6" sx={{ fontSize: '1.05rem', fontWeight: 600, mb: 0.5 }}>
            Merchant payment ID
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Share this ID with a customer. They can enter it in Pay merchant to pay your business.
          </Typography>
          {walletQuery.isLoading ? (
            <Skeleton variant="rounded" height={48} />
          ) : wallet ? (
            <Paper
              variant="outlined"
              sx={{
                p: 1.5,
                bgcolor: 'background.default',
                display: 'flex',
                flexDirection: { xs: 'column', sm: 'row' },
                justifyContent: 'space-between',
                alignItems: { xs: 'flex-start', sm: 'center' },
                gap: 1.5,
              }}
            >
              <Typography
                variant="body2"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: { xs: '0.8rem', sm: '0.9rem' },
                  fontWeight: 600,
                  wordBreak: 'break-all',
                }}
              >
                {wallet.ledgerAccountId}
              </Typography>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexShrink: 0, alignSelf: { xs: 'stretch', sm: 'auto' } }}>
                <CopyButton value={wallet.ledgerAccountId} label="merchant payment ID" />
              </Stack>
            </Paper>
          ) : (
            <Alert severity="warning">Merchant payment ID is currently unavailable.</Alert>
          )}
        </CardContent>
      </Card>

      {/* D. Recent Customer Payments */}
      <Card>
        <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
          <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2, flexWrap: 'wrap', gap: 1 }}>
            <Box>
              <Typography component="h2" variant="h6" sx={{ fontSize: '1.15rem', fontWeight: 600 }}>
                Recent customer payments
              </Typography>
              <Typography variant="body2" color="text.secondary">
                Latest customer payments received by your business.
              </Typography>
            </Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <Tooltip title="Refresh recent payments">
                <span>
                  <IconButton
                    aria-label="Refresh recent customer payments"
                    size="small"
                    onClick={() => { void recentPaymentsQuery.refetch(); }}
                    disabled={recentPaymentsQuery.isFetching}
                  >
                    <RefreshIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
              <Button
                component={RouterLink}
                to="/app/activity?type=payments"
                endIcon={<ArrowForwardIcon fontSize="small" />}
                size="small"
                sx={{ fontWeight: 600 }}
              >
                View all payments
              </Button>
            </Stack>
          </Stack>

          {recentPaymentsQuery.isLoading ? (
            <Box sx={{ py: 2 }}>
              <Skeleton variant="rectangular" height={220} sx={{ borderRadius: 1 }} />
            </Box>
          ) : recentPaymentsQuery.isError ? (
            <Alert
              severity="error"
              action={
                <Button color="inherit" size="small" onClick={() => { void recentPaymentsQuery.refetch(); }}>
                  Retry
                </Button>
              }
            >
              {getErrorMessage(recentPaymentsQuery.error, 'Unable to load recent customer payments.')}
            </Alert>
          ) : recentPayments.length === 0 ? (
            <Box sx={{ py: 5, textAlign: 'center' }}>
              <Typography variant="body2" color="text.secondary" sx={{ fontWeight: 500 }}>
                No customer payments yet.
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                Payments made by customers with Pay merchant will appear here.
              </Typography>
            </Box>
          ) : (
            <>
              {/* Mobile View: Cards */}
              <Box sx={{ display: { xs: 'block', md: 'none' } }}>
                {recentPayments.map((payment: Payment) => (
                  <Box key={payment.paymentId} sx={{ py: 2, borderTop: '1px solid', borderColor: 'divider' }}>
                    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 0.5 }}>
                      <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                        {formatMinorUnitsToInr(payment.grossAmountMinor)}
                      </Typography>
                      <StatusBadge status={payment.status} />
                    </Stack>
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      Fee {formatMinorUnitsToInr(payment.feeAmountMinor)} · Net {formatMinorUnitsToInr(payment.merchantNetAmountMinor)}
                    </Typography>
                    {payment.refundStatus && payment.refundStatus !== 'NOT_REFUNDED' && (
                      <Typography variant="caption" color="warning.main" sx={{ display: 'block', fontWeight: 600, mt: 0.25 }}>
                        {payment.refundStatus === 'FULLY_REFUNDED' ? 'Fully refunded' : `${formatMinorUnitsToInr(payment.refundedAmountMinor ?? '0')} refunded`}
                      </Typography>
                    )}
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                      {formatDateTime(payment.createdAt)}
                    </Typography>
                    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mt: 1 }}>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                        {payment.paymentId.slice(0, 8)}…{payment.paymentId.slice(-4)}
                      </Typography>
                      <Button component={RouterLink} to={`/app/payments/${payment.paymentId}`} size="small" aria-label={`View payment ${payment.paymentId}`}>
                        Details
                      </Button>
                    </Stack>
                  </Box>
                ))}
              </Box>

              {/* Desktop View: Table */}
              <TableContainer tabIndex={0} role="region" aria-label="Recent customer payments table" sx={{ display: { xs: 'none', md: 'block' } }}>
                <Table aria-label="Recent customer payments" size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Payment ID</TableCell>
                      <TableCell align="right">Gross</TableCell>
                      <TableCell align="right">Platform fee</TableCell>
                      <TableCell align="right">Merchant net</TableCell>
                      <TableCell>Refunded</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Date</TableCell>
                      <TableCell align="right">Details</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {recentPayments.map((payment: Payment) => (
                      <TableRow key={payment.paymentId} hover>
                        <TableCell>
                          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                            <Tooltip title={payment.paymentId}>
                              <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                                {payment.paymentId.slice(0, 8)}…{payment.paymentId.slice(-4)}
                              </Typography>
                            </Tooltip>
                            <CopyButton value={payment.paymentId} label="payment ID" />
                          </Stack>
                        </TableCell>
                        <TableCell align="right" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                          {formatMinorUnitsToInr(payment.grossAmountMinor)}
                        </TableCell>
                        <TableCell align="right" sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>
                          {formatMinorUnitsToInr(payment.feeAmountMinor)}
                        </TableCell>
                        <TableCell align="right" sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums', fontWeight: 600, color: 'secondary.main' }}>
                          {formatMinorUnitsToInr(payment.merchantNetAmountMinor)}
                        </TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>
                          {payment.refundStatus === 'FULLY_REFUNDED' ? (
                            <Typography variant="caption" sx={{ fontWeight: 600, color: 'warning.dark' }}>Fully refunded</Typography>
                          ) : payment.refundStatus === 'PARTIALLY_REFUNDED' ? (
                            <Typography variant="caption" sx={{ fontWeight: 600, color: 'warning.main' }}>
                              {formatMinorUnitsToInr(payment.refundedAmountMinor ?? '0')} refunded
                            </Typography>
                          ) : (
                            <Typography variant="caption" color="text.secondary">Not refunded</Typography>
                          )}
                        </TableCell>
                        <TableCell>
                          <StatusBadge status={payment.status} />
                        </TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>
                          <Typography variant="caption">{formatDateTime(payment.createdAt)}</Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Button component={RouterLink} to={`/app/payments/${payment.paymentId}`} size="small" aria-label={`View details for payment ${payment.paymentId}`}>
                            Details
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
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
