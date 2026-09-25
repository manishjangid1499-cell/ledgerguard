import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Grid,
  InputAdornment,
  Skeleton,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { walletApi } from '../../wallet/api/walletApi';
import { financialApi } from '../api';
import { Payout, Posted } from '../types';
import { formatMinorUnitsToInr, parseInrToMinorUnits } from '../../shared/utils/money';
import { getErrorMessage } from '../../shared/api/errorMessage';

export const WithdrawalSection = ({ onWithdrawalSuccess }: { onWithdrawalSuccess?: (payout: Posted<Payout>) => void }) => {
  const queryClient = useQueryClient();
  const [amountStr, setAmountStr] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [successPayout, setSuccessPayout] = useState<Posted<Payout> | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState<string>(() => crypto.randomUUID());

  const { data: wallet, isLoading: isWalletLoading, isError: isWalletError, error: walletError, refetch: refetchWallet } = useQuery({
    queryKey: ['wallet'],
    queryFn: walletApi.getMyWallet,
    staleTime: 15_000,
  });

  const availableMinor = wallet ? BigInt(wallet.availableBalanceMinor) : 0n;

  // Validate parsed amount
  const parsed = parseInrToMinorUnits(amountStr);
  const requestedMinor = parsed.ok && parsed.minorUnits !== undefined ? BigInt(parsed.minorUnits) : 0n;
  const isAmountValid = parsed.ok && requestedMinor > 0n;
  const exceedsAvailable = isAmountValid && requestedMinor > availableMinor;
  const remainingAfterWithdrawal = exceedsAvailable ? 0n : availableMinor - requestedMinor;

  const handleUseMaximum = () => {
    if (!wallet) return;
    const minor = BigInt(wallet.availableBalanceMinor);
    if (minor <= 0n) {
      setAmountStr('0.00');
    } else {
      const rupees = minor / 100n;
      const paise = minor % 100n;
      setAmountStr(`${rupees}.${paise.toString().padStart(2, '0')}`);
    }
    setValidationError(null);
  };

  const handleInitiateClick = (e: React.FormEvent) => {
    e.preventDefault();
    setValidationError(null);
    setSubmitError(null);

    if (!amountStr.trim()) {
      setValidationError('Please enter an amount to withdraw.');
      return;
    }
    if (!parsed.ok || parsed.minorUnits === undefined || parsed.minorUnits <= 0) {
      setValidationError(parsed.error || 'Enter a positive amount with up to 2 decimal places.');
      return;
    }
    if (requestedMinor > availableMinor) {
      setValidationError(`Requested amount exceeds available balance of ${formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')}.`);
      return;
    }

    setConfirmOpen(true);
  };

  const handleConfirmWithdrawal = async () => {
    if (!parsed.ok || parsed.minorUnits === undefined) return;
    setIsSubmitting(true);
    setSubmitError(null);

    try {
      const result = await financialApi.withdraw({ amountMinor: String(parsed.minorUnits) }, idempotencyKey);
      setSuccessPayout(result);
      setConfirmOpen(false);
      setAmountStr('');
      // Generate new idempotency key for next operation
      setIdempotencyKey(crypto.randomUUID());

      // Invalidate relevant queries
      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
      void queryClient.invalidateQueries({ queryKey: ['payouts', 'list'] });
      void queryClient.invalidateQueries({ queryKey: ['merchantSummary'] });

      if (onWithdrawalSuccess) {
        onWithdrawalSuccess(result);
      }
    } catch (err: unknown) {
      setConfirmOpen(false);
      setSubmitError(getErrorMessage(err, 'Withdrawal request could not be processed. Review your activity before retrying.'));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Box sx={{ mb: 4 }}>
      {successPayout && (
        <Alert
          severity="success"
          onClose={() => setSuccessPayout(null)}
          sx={{ mb: 3 }}
          action={
            <Button component={RouterLink} to={`/app/payouts/${successPayout.payoutId}`} color="inherit" size="small">
              View details
            </Button>
          }
        >
          Withdrawal of {formatMinorUnitsToInr(successPayout.amountMinor)} initiated. Payout ID: {successPayout.payoutId}. The amount is reserved on hold while the simulated payout provider confirms the result.
        </Alert>
      )}

      {submitError && (
        <Alert severity="error" onClose={() => setSubmitError(null)} sx={{ mb: 3 }}>
          {submitError}
        </Alert>
      )}

      <Grid container spacing={3}>
        {/* Left Column: Withdraw Funds Form */}
        <Grid size={{ xs: 12, md: 7 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
              <Typography component="h2" variant="h6" sx={{ fontWeight: 600, mb: 1 }}>
                Withdraw funds
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 3, lineHeight: 1.6 }}>
                Move money from your merchant balance through the simulated payout provider. The requested amount remains on hold until the provider confirms the result.
              </Typography>

              <Box component="form" noValidate onSubmit={handleInitiateClick}>
                <Stack spacing={2.5}>
                  <TextField
                    id="payout-amount"
                    label="Amount to withdraw (INR)"
                    required
                    fullWidth
                    value={amountStr}
                    disabled={isSubmitting}
                    error={Boolean(validationError || exceedsAvailable)}
                    helperText={
                      validationError ||
                      (exceedsAvailable
                        ? `Amount exceeds available balance (${formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')})`
                        : 'Enter a positive amount with up to 2 decimal places.')
                    }
                    onChange={e => {
                      setAmountStr(e.target.value);
                      setValidationError(null);
                      setSubmitError(null);
                    }}
                    slotProps={{
                      htmlInput: { inputMode: 'decimal' },
                      input: {
                        startAdornment: <InputAdornment position="start">₹</InputAdornment>,
                      },
                    }}
                  />

                  <Button
                    type="submit"
                    variant="contained"
                    disabled={isSubmitting || !amountStr || exceedsAvailable || isWalletLoading}
                    sx={{ alignSelf: { xs: 'stretch', sm: 'flex-start' }, fontWeight: 600, px: 3 }}
                  >
                    {isSubmitting ? 'Processing…' : 'Withdraw funds'}
                  </Button>
                </Stack>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        {/* Right Column: Payout Summary & Wallet Context */}
        <Grid size={{ xs: 12, md: 5 }}>
          <Card sx={{ height: '100%', bgcolor: 'background.default' }}>
            <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
              <Typography component="h3" variant="subtitle1" sx={{ fontWeight: 600, mb: 2 }}>
                Wallet context
              </Typography>

              {isWalletLoading ? (
                <Stack spacing={1.5}>
                  <Skeleton variant="rectangular" height={24} />
                  <Skeleton variant="rectangular" height={24} />
                  <Skeleton variant="rectangular" height={24} />
                </Stack>
              ) : isWalletError ? (
                <Alert severity="error" action={<Button size="small" color="inherit" onClick={() => { void refetchWallet(); }}>Retry</Button>}>
                  {getErrorMessage(walletError, 'Unable to load wallet context.')}
                </Alert>
              ) : wallet ? (
                <Stack spacing={2}>
                  <Box sx={{ p: 2, bgcolor: 'background.paper', borderRadius: 1, border: '1px solid', borderColor: 'divider' }}>
                    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
                      <Typography variant="body2" color="text.secondary">Available to withdraw</Typography>
                      <Typography variant="subtitle1" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', color: 'primary.main' }}>
                        {formatMinorUnitsToInr(wallet.availableBalanceMinor)}
                      </Typography>
                    </Stack>
                    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 1 }}>
                      <Typography variant="body2" color="text.secondary">Currently on hold</Typography>
                      <Typography variant="body2" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                        {formatMinorUnitsToInr(wallet.activeHoldAmountMinor)}
                      </Typography>
                    </Stack>
                    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                      <Typography variant="body2" color="text.secondary">Requested amount</Typography>
                      <Typography variant="body2" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                        {isAmountValid ? formatMinorUnitsToInr(String(requestedMinor)) : '₹0.00'}
                      </Typography>
                    </Stack>
                    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                      <Typography variant="body2" color="text.secondary">Remaining balance afterward</Typography>
                      <Typography
                        variant="body2"
                        sx={{
                          fontWeight: 700,
                          fontVariantNumeric: 'tabular-nums',
                          color: exceedsAvailable ? 'error.main' : 'text.primary',
                        }}
                      >
                        {formatMinorUnitsToInr(String(remainingAfterWithdrawal))}
                      </Typography>
                    </Stack>
                  </Box>

                  <Button
                    variant="outlined"
                    size="small"
                    onClick={handleUseMaximum}
                    disabled={isSubmitting || availableMinor <= 0n}
                    sx={{ alignSelf: 'flex-start' }}
                  >
                    Use maximum ({formatMinorUnitsToInr(wallet.availableBalanceMinor)})
                  </Button>
                </Stack>
              ) : null}
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Confirmation Dialog */}
      <Dialog
        open={confirmOpen}
        onClose={() => { if (!isSubmitting) setConfirmOpen(false); }}
        aria-labelledby="confirm-withdrawal-dialog-title"
        aria-describedby="confirm-withdrawal-dialog-description"
      >
        <DialogTitle id="confirm-withdrawal-dialog-title" sx={{ fontWeight: 700 }}>
          Withdraw {isAmountValid ? formatMinorUnitsToInr(String(requestedMinor)) : ''}?
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="confirm-withdrawal-dialog-description" sx={{ color: 'text.secondary', mb: 2 }}>
            This amount will be reserved while the simulated payout provider processes the request.
          </DialogContentText>
          <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
            <Stack spacing={1}>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">Current available balance:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')}</Typography>
              </Stack>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">Withdrawal amount:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 600, color: 'primary.main' }}>{formatMinorUnitsToInr(String(requestedMinor))}</Typography>
              </Stack>
              <Stack direction="row" sx={{ justifyContent: 'space-between', pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                <Typography variant="body2" color="text.secondary">Remaining balance afterward:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>{formatMinorUnitsToInr(String(remainingAfterWithdrawal))}</Typography>
              </Stack>
            </Stack>
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setConfirmOpen(false)} disabled={isSubmitting} color="inherit">
            Cancel
          </Button>
          <Button
            onClick={handleConfirmWithdrawal}
            variant="contained"
            color="primary"
            disabled={isSubmitting}
            startIcon={isSubmitting ? <CircularProgress size={16} color="inherit" /> : undefined}
          >
            {isSubmitting ? 'Submitting…' : 'Confirm withdrawal'}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};
