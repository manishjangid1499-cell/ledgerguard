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
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import PaymentsIcon from '@mui/icons-material/Payments';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ReplayIcon from '@mui/icons-material/Replay';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { financialApi } from '../api';
import { walletApi } from '../../wallet/api/walletApi';
import { formatMinorUnitsToInr, parseInrToMinorUnits } from '../../shared/utils/money';
import { financialError, isUnconfirmed } from '../feedback';
import { Payment, Posted } from '../types';

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

export interface CustomerPaymentFormProps {
  onSuccess?: (payment: Posted<Payment>) => void;
}

export const CustomerPaymentForm: React.FC<CustomerPaymentFormProps> = ({ onSuccess }) => {
  const queryClient = useQueryClient();

  const [merchantId, setMerchantId] = useState('');
  const [amountStr, setAmountStr] = useState('');
  const [clientError, setClientError] = useState<{ field: 'merchant' | 'amount'; message: string } | null>(null);
  const [lastSuccess, setLastSuccess] = useState<Posted<Payment> | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const submitting = useRef(false);
  const merchantInput = useRef<HTMLInputElement>(null);
  const amountInput = useRef<HTMLInputElement>(null);

  const { data: wallet } = useQuery({
    queryKey: ['wallet'],
    queryFn: walletApi.getMyWallet,
    staleTime: 30_000,
  });

  const availableMinor = wallet ? BigInt(wallet.availableBalanceMinor) : 0n;
  const isWalletInactive = Boolean(wallet && wallet.status && wallet.status !== 'ACTIVE');

  const idempotencyRef = useRef<{
    key: string;
    boundMerchant: string;
    boundAmount: string;
  }>({
    key: generateUUID(),
    boundMerchant: '',
    boundAmount: '',
  });

  const handleMerchantChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value.trim();
    setMerchantId(val);
    setClientError(null);
    setLastSuccess(null);
    paymentMutation.reset();

    if (val !== idempotencyRef.current.boundMerchant) {
      idempotencyRef.current = {
        key: generateUUID(),
        boundMerchant: val,
        boundAmount: amountStr.trim(),
      };
    }
  };

  const handleAmountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setAmountStr(val);
    setClientError(null);
    setLastSuccess(null);
    paymentMutation.reset();

    if (val.trim() !== idempotencyRef.current.boundAmount) {
      idempotencyRef.current = {
        key: generateUUID(),
        boundMerchant: merchantId.trim(),
        boundAmount: val.trim(),
      };
    }
  };

  const paymentMutation = useMutation({
    mutationFn: async ({
      merchantAccountId,
      minorUnits,
      key,
    }: {
      merchantAccountId: string;
      minorUnits: number;
      key: string;
    }) => {
      return financialApi.payMerchant(
        {
          merchantLedgerAccountId: merchantAccountId,
          amountMinor: minorUnits,
        },
        key
      );
    },
    onSuccess: (data) => {
      submitting.current = false;
      setLastSuccess(data);
      setMerchantId('');
      setAmountStr('');
      setClientError(null);
      setConfirmOpen(false);

      idempotencyRef.current = {
        key: generateUUID(),
        boundMerchant: '',
        boundAmount: '',
      };

      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
      void queryClient.invalidateQueries({ queryKey: ['payments'] });
      void queryClient.invalidateQueries({ queryKey: ['payments', 'recent'] });

      if (onSuccess) {
        onSuccess(data);
      }
    },
    onError: () => {
      submitting.current = false;
      setConfirmOpen(false);
    },
  });

  const parsed = parseInrToMinorUnits(amountStr);
  const requestedMinor = parsed.ok && parsed.minorUnits !== undefined ? BigInt(parsed.minorUnits) : 0n;
  const isAmountValid = parsed.ok && requestedMinor > 0n;
  const exceedsAvailable = wallet && isAmountValid && requestedMinor > availableMinor;
  const remainingAfterPayment = exceedsAvailable ? 0n : (wallet ? availableMinor - requestedMinor : 0n);

  const handleInitiateClick = (e: React.FormEvent) => {
    e.preventDefault();
    setClientError(null);
    setLastSuccess(null);
    paymentMutation.reset();

    if (isWalletInactive) {
      setClientError({ field: 'amount', message: `Your wallet status is ${wallet?.status}. Payments are unavailable while your wallet is not active.` });
      return;
    }

    const mId = merchantId.trim();
    if (!mId) {
      setClientError({ field: 'merchant', message: 'Merchant payment ID is required.' });
      merchantInput.current?.focus();
      return;
    }

    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (!uuidRegex.test(mId)) {
      setClientError({ field: 'merchant', message: 'Enter a valid 36-character merchant payment ID.' });
      merchantInput.current?.focus();
      return;
    }

    if (!amountStr.trim()) {
      setClientError({ field: 'amount', message: 'Payment amount is required.' });
      amountInput.current?.focus();
      return;
    }

    if (!parsed.ok || parsed.minorUnits === undefined || parsed.minorUnits <= 0) {
      setClientError({ field: 'amount', message: parsed.error || 'Enter a positive amount with up to 2 decimal places.' });
      amountInput.current?.focus();
      return;
    }

    if (exceedsAvailable) {
      setClientError({
        field: 'amount',
        message: `Amount exceeds available balance of ${formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')}.`,
      });
      amountInput.current?.focus();
      return;
    }

    setConfirmOpen(true);
  };

  const handleConfirmPayment = () => {
    if (submitting.current || !parsed.ok || parsed.minorUnits === undefined) return;

    const mId = merchantId.trim();
    idempotencyRef.current.boundMerchant = mId;
    idempotencyRef.current.boundAmount = amountStr.trim();

    submitting.current = true;
    paymentMutation.mutate({
      merchantAccountId: mId,
      minorUnits: parsed.minorUnits,
      key: idempotencyRef.current.key,
    });
  };

  const isUnconfirmedError = isUnconfirmed(paymentMutation.error);

  return (
    <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 0.5 }}>
          Pay merchant
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5 }}>
          Send a payment to a business using their merchant payment ID.
        </Typography>

        {isWalletInactive && (
          <Alert severity="warning" sx={{ mb: 2.5 }}>
            Your wallet status is {wallet?.status}. Payments are unavailable while your wallet is not active.
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
                <Button component={RouterLink} to={`/app/payments/${lastSuccess.paymentId}`} color="inherit" size="small">
                  View details
                </Button>
              }
            >
              <AlertTitle sx={{ fontWeight: 700 }}>
                {lastSuccess.replayed ? 'Payment already completed' : 'Payment completed'}
              </AlertTitle>
              {lastSuccess.replayed && 'Your original payment is confirmed. No additional funds were moved. '}
              Payment ID: <code style={{ wordBreak: 'break-all' }}>{lastSuccess.paymentId}</code> · Status: Completed
            </Alert>
          )}
        </Collapse>

        <Collapse in={paymentMutation.isError}>
          {paymentMutation.isError && (
            <Alert
              severity={isUnconfirmedError ? 'warning' : 'error'}
              sx={{ mb: 2.5 }}
              action={
                <Button
                  color="inherit"
                  size="small"
                  startIcon={<ReplayIcon />}
                  onClick={handleConfirmPayment}
                  disabled={paymentMutation.isPending}
                >
                  Retry
                </Button>
              }
            >
              <AlertTitle sx={{ fontWeight: 700 }}>
                {isUnconfirmedError ? 'Payment outcome unconfirmed' : 'Payment could not be completed'}
              </AlertTitle>
              {isUnconfirmedError
                ? 'The payment outcome could not be confirmed. Check your Activity or refresh your wallet before retrying.'
                : financialError(paymentMutation.error, 'The payment could not be processed. Review the details and try again.')}
            </Alert>
          )}
        </Collapse>

        <Box component="form" onSubmit={handleInitiateClick} noValidate aria-busy={paymentMutation.isPending}>
          <Stack spacing={2.5}>
            <TextField
              id="payment-merchant-id"
              inputRef={merchantInput}
              label="Merchant payment ID"
              error={clientError?.field === 'merchant'}
              value={merchantId}
              onChange={handleMerchantChange}
              fullWidth
              required
              disabled={paymentMutation.isPending || isWalletInactive}
              helperText={
                clientError?.field === 'merchant'
                  ? clientError.message
                  : 'Ask the merchant for the payment ID shown on their dashboard.'
              }
              slotProps={{
                input: {
                  sx: { fontFamily: 'monospace', fontSize: '0.9rem' },
                },
              }}
            />

            <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <Typography variant="caption" color="text.secondary">
                Available: <strong>{formatMinorUnitsToInr(wallet?.availableBalanceMinor ?? '0')}</strong>
              </Typography>
            </Box>

            <TextField
              id="payment-amount"
              inputRef={amountInput}
              label="Amount (INR)"
              error={clientError?.field === 'amount'}
              placeholder="e.g. 250.00"
              value={amountStr}
              onChange={handleAmountChange}
              fullWidth
              required
              disabled={paymentMutation.isPending || isWalletInactive}
              helperText={clientError?.field === 'amount' ? clientError.message : 'Enter a positive amount with up to 2 decimal places.'}
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
                disabled={paymentMutation.isPending || !merchantId.trim() || !amountStr.trim() || isWalletInactive}
                startIcon={
                  paymentMutation.isPending ? (
                    <CircularProgress size={20} color="inherit" aria-hidden="true" />
                  ) : (
                    <PaymentsIcon />
                  )
                }
                sx={{ px: 4, py: 1.2, fontWeight: 700, width: { xs: '100%', sm: 'auto' } }}
              >
                {paymentMutation.isPending ? 'Processing payment…' : 'Pay merchant'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>

      {/* Confirmation Dialog */}
      <Dialog
        open={confirmOpen}
        onClose={() => { if (!paymentMutation.isPending) setConfirmOpen(false); }}
        aria-labelledby="confirm-payment-dialog-title"
        aria-describedby="confirm-payment-dialog-description"
      >
        <DialogTitle id="confirm-payment-dialog-title" sx={{ fontWeight: 700 }}>
          {`Pay ${isAmountValid ? formatMinorUnitsToInr(String(requestedMinor)) : ''} to this merchant?`}
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="confirm-payment-dialog-description" sx={{ color: 'text.secondary', mb: 2 }}>
            Completed payments cannot be manually reversed by the customer. Eligible refunds are issued at the merchant&apos;s discretion.
          </DialogContentText>
          <Box sx={{ p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
            <Stack spacing={1}>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">Merchant payment ID:</Typography>
                <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                  {merchantId.length >= 12
                    ? `${merchantId.slice(0, 8)}…${merchantId.slice(-4)}`
                    : merchantId}
                </Typography>
              </Stack>
              <Stack direction="row" sx={{ justifyContent: 'space-between' }}>
                <Typography variant="body2" color="text.secondary">Payment amount:</Typography>
                <Typography variant="body2" sx={{ fontWeight: 600, color: 'primary.main' }}>
                  {formatMinorUnitsToInr(String(requestedMinor))}
                </Typography>
              </Stack>
              {wallet && (
                <Stack direction="row" sx={{ justifyContent: 'space-between', pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                  <Typography variant="body2" color="text.secondary">Remaining available balance:</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {formatMinorUnitsToInr(String(remainingAfterPayment))}
                  </Typography>
                </Stack>
              )}
            </Stack>
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2.5 }}>
          <Button onClick={() => setConfirmOpen(false)} disabled={paymentMutation.isPending} color="inherit">
            Cancel
          </Button>
          <Button
            onClick={handleConfirmPayment}
            variant="contained"
            color="primary"
            disabled={paymentMutation.isPending}
            startIcon={paymentMutation.isPending ? <CircularProgress size={16} color="inherit" /> : undefined}
          >
            {paymentMutation.isPending ? 'Processing…' : 'Confirm payment'}
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
};
