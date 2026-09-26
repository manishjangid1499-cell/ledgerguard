import React, { useState, useRef } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Card,
  CardContent,
  CircularProgress,
  Collapse,
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
import AddCardIcon from '@mui/icons-material/AddCard';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ReplayIcon from '@mui/icons-material/Replay';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { financialApi } from '../api';
import { walletApi } from '../../wallet/api/walletApi';
import { formatMinorUnitsToInr, parseInrToMinorUnits } from '../../shared/utils/money';
import { financialError, isUnconfirmed } from '../feedback';
import { Funding, Posted } from '../types';

function generateUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export interface CustomerFundingFormProps {
  onSuccess?: (funding: Posted<Funding>) => void;
}

export const CustomerFundingForm: React.FC<CustomerFundingFormProps> = ({ onSuccess }) => {
  const queryClient = useQueryClient();

  const [amountStr, setAmountStr] = useState('');
  const [clientError, setClientError] = useState<string | null>(null);
  const [lastSuccess, setLastSuccess] = useState<Posted<Funding> | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const submitting = useRef(false);
  const amountInput = useRef<HTMLInputElement>(null);

  const { data: wallet, isLoading: isWalletLoading } = useQuery({
    queryKey: ['wallet'],
    queryFn: walletApi.getMyWallet,
    staleTime: 30_000,
  });

  const availableMinor = wallet ? BigInt(wallet.availableBalanceMinor) : 0n;
  const isWalletInactive = Boolean(wallet && wallet.status && wallet.status !== 'ACTIVE');

  const idempotencyRef = useRef<{
    key: string;
    boundAmount: string;
  }>({
    key: generateUUID(),
    boundAmount: '',
  });

  const handleAmountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setAmountStr(val);
    setClientError(null);
    setLastSuccess(null);
    fundingMutation.reset();

    if (val.trim() !== idempotencyRef.current.boundAmount) {
      idempotencyRef.current = {
        key: generateUUID(),
        boundAmount: val.trim(),
      };
    }
  };

  const fundingMutation = useMutation({
    mutationFn: async ({
      minorUnits,
      key,
    }: {
      minorUnits: number;
      key: string;
    }) => {
      return financialApi.addMoney(
        { amountMinor: String(minorUnits) },
        key
      );
    },
    retry: false,
    onSuccess: (data) => {
      setLastSuccess(data);
      setAmountStr('');
      setConfirmOpen(false);
      idempotencyRef.current = {
        key: generateUUID(),
        boundAmount: '',
      };
      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
      void queryClient.invalidateQueries({ queryKey: ['funding'] });
      if (onSuccess) {
        onSuccess(data);
      }
    },
    onError: () => {
      setConfirmOpen(false);
    },
    onSettled: () => {
      submitting.current = false;
    },
  });

  const parsed = parseInrToMinorUnits(amountStr);
  const requestedMinor = parsed.ok && parsed.minorUnits !== undefined ? BigInt(parsed.minorUnits) : 0n;
  const isAmountValid = parsed.ok && requestedMinor > 0n;
  const expectedAvailableAfterFunding = availableMinor + requestedMinor;

  const handleInitiateClick = (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting.current) return;
    setClientError(null);
    setLastSuccess(null);

    if (isWalletInactive) {
      setClientError(`Your wallet status is ${wallet?.status}. Funding is unavailable while your wallet is not active.`);
      return;
    }

    if (!parsed.ok || parsed.minorUnits === undefined || parsed.minorUnits <= 0) {
      setClientError(parsed.error || 'Enter a positive amount with up to 2 decimal places.');
      amountInput.current?.focus();
      return;
    }

    setConfirmOpen(true);
  };

  const handleConfirmFunding = () => {
    if (submitting.current || !parsed.ok || parsed.minorUnits === undefined) return;

    idempotencyRef.current.boundAmount = amountStr.trim();
    submitting.current = true;
    fundingMutation.mutate({
      minorUnits: parsed.minorUnits,
      key: idempotencyRef.current.key,
    });
  };

  const isUnconfirmedError = isUnconfirmed(fundingMutation.error);

  return (
    <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 0.5 }}>
          Add money
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Add funds to your wallet through LedgerGuard’s simulated funding provider.
        </Typography>

        <Alert severity="info" variant="outlined" sx={{ mb: 2.5 }}>
          <strong>Simulation rail:</strong> LedgerGuard simulates payment rail funding. Deposited funds are credited to your wallet upon confirmed provider settlement.
        </Alert>

        {isWalletInactive && (
          <Alert severity="warning" sx={{ mb: 2.5 }}>
            Your wallet status is {wallet?.status}. Funding is unavailable while your wallet is not active.
          </Alert>
        )}

        <Collapse in={!!lastSuccess}>
          {lastSuccess && (
            <Alert
              severity="success"
              icon={<CheckCircleIcon fontSize="inherit" />}
              sx={{ mb: 2.5 }}
              onClose={() => setLastSuccess(null)}
              action={
                <Button component={RouterLink} to={`/app/funding/${lastSuccess.fundingId}`} color="inherit" size="small">
                  View details
                </Button>
              }
            >
              <AlertTitle sx={{ fontWeight: 700 }}>
                {lastSuccess.replayed ? 'Funding already recorded' : 'Funding request submitted'}
              </AlertTitle>
              {lastSuccess.replayed && 'Your original funding request is confirmed. '}
              Funding ID: <code style={{ wordBreak: 'break-all' }}>{lastSuccess.fundingId}</code> · Status: {lastSuccess.status}
            </Alert>
          )}
        </Collapse>

        <Collapse in={fundingMutation.isError}>
          {fundingMutation.isError && (
            <Alert
              severity={isUnconfirmedError ? 'warning' : 'error'}
              sx={{ mb: 2.5 }}
              action={
                <Button
                  color="inherit"
                  size="small"
                  startIcon={<ReplayIcon />}
                  onClick={handleConfirmFunding}
                  disabled={fundingMutation.isPending}
                >
                  Retry
                </Button>
              }
            >
              <AlertTitle sx={{ fontWeight: 700 }}>
                {isUnconfirmedError ? 'Funding outcome unconfirmed' : 'Funding request could not be processed'}
              </AlertTitle>
              {isUnconfirmedError
                ? 'The funding outcome could not be confirmed. Check your Activity or refresh your wallet before retrying.'
                : financialError(fundingMutation.error, 'The funding request could not be processed. Review the details and try again.')}
            </Alert>
          )}
        </Collapse>

        <Grid container spacing={3}>
          {/* Left Column: Form */}
          <Grid size={{ xs: 12, md: 7 }}>
            <Box component="form" onSubmit={handleInitiateClick} noValidate aria-busy={fundingMutation.isPending}>
              <Stack spacing={2.5}>
                <TextField
                  id="funding-amount"
                  inputRef={amountInput}
                  label="Amount to add (INR)"
                  error={Boolean(clientError)}
                  placeholder="e.g. 500.00"
                  value={amountStr}
                  onChange={handleAmountChange}
                  fullWidth
                  required
                  disabled={fundingMutation.isPending || isWalletInactive}
                  helperText={clientError || 'Enter a positive amount with up to 2 decimal places.'}
                  slotProps={{
                    htmlInput: { inputMode: 'decimal' },
                    input: {
                      startAdornment: <InputAdornment position="start">₹</InputAdornment>,
                      sx: { fontWeight: 600, fontSize: '1.05rem' },
                    },
                  }}
                />

                <Box sx={{ display: 'flex', justifyContent: 'flex-end', pt: 1 }}>
                  <Button
                    type="submit"
                    variant="contained"
                    size="large"
                    disabled={fundingMutation.isPending || !amountStr.trim() || isWalletInactive}
                    startIcon={
                      fundingMutation.isPending ? (
                        <CircularProgress size={20} color="inherit" aria-hidden="true" />
                      ) : (
                        <AddCardIcon />
                      )
                    }
                    sx={{ px: 4, py: 1.2, fontWeight: 700, width: { xs: '100%', sm: 'auto' } }}
                  >
                    {fundingMutation.isPending ? 'Processing funding…' : 'Add money'}
                  </Button>
                </Box>
              </Stack>
            </Box>
          </Grid>

          {/* Right Column: Context Preview */}
          <Grid size={{ xs: 12, md: 5 }}>
            <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1, border: '1px solid', borderColor: 'divider' }}>
              <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1.5 }}>
                Wallet context
              </Typography>
              {isWalletLoading ? (
                <Skeleton variant="rectangular" height={100} />
              ) : (
                <Stack spacing={1}>
                  <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">Available balance:</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')}
                    </Typography>
                  </Stack>
                  <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">Currently on hold:</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {formatMinorUnitsToInr(wallet?.activeHoldAmountMinor ?? '0')}
                    </Typography>
                  </Stack>
                  <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                    <Typography variant="body2" color="text.secondary">Amount to add:</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 600, color: 'primary.main' }}>
                      {isAmountValid ? formatMinorUnitsToInr(String(requestedMinor)) : '₹0.00'}
                    </Typography>
                  </Stack>
                  <Stack direction="row" sx={{ justifyContent: 'space-between', pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                    <Typography variant="body2" color="text.secondary">Expected balance after confirmed funding:</Typography>
                    <Typography variant="body2" sx={{ fontWeight: 700, color: 'success.main' }}>
                      {formatMinorUnitsToInr(String(expectedAvailableAfterFunding))}
                    </Typography>
                  </Stack>
                </Stack>
              )}
            </Box>
          </Grid>
        </Grid>
      </CardContent>

      {/* Confirmation Dialog */}
      <Dialog
        open={confirmOpen}
        onClose={() => { if (!fundingMutation.isPending) setConfirmOpen(false); }}
        aria-labelledby="confirm-funding-dialog-title"
        aria-describedby="confirm-funding-dialog-description"
      >
        <DialogTitle id="confirm-funding-dialog-title" sx={{ fontWeight: 700 }}>
          {`Add ${isAmountValid ? formatMinorUnitsToInr(String(requestedMinor)) : ''} to your wallet?`}
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="confirm-funding-dialog-description" sx={{ color: 'text.secondary', mb: 2 }}>
            This request uses LedgerGuard’s simulated funding provider.
          </DialogContentText>
          <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
            <Stack spacing={1}>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">Current available balance:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')}
                </Typography>
              </Stack>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">Amount to add:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 600, color: 'primary.main' }}>
                  {formatMinorUnitsToInr(String(requestedMinor))}
                </Typography>
              </Stack>
              <Stack direction="row" sx={{ justifyContent: 'space-between', pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                <Typography variant="body2" color="text.secondary">Expected balance afterward:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>
                  {formatMinorUnitsToInr(String(expectedAvailableAfterFunding))}
                </Typography>
              </Stack>
            </Stack>
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setConfirmOpen(false)} disabled={fundingMutation.isPending} color="inherit">
            Cancel
          </Button>
          <Button
            onClick={handleConfirmFunding}
            variant="contained"
            color="primary"
            disabled={fundingMutation.isPending}
            startIcon={fundingMutation.isPending ? <CircularProgress size={16} color="inherit" /> : undefined}
          >
            {fundingMutation.isPending ? 'Processing…' : 'Confirm funding'}
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
};
