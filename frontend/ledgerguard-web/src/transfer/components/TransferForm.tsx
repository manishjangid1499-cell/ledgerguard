import React, { useState, useRef } from 'react';
import {
  Card,
  CardContent,
  Typography,
  TextField,
  Button,
  Stack,
  Alert,
  AlertTitle,
  CircularProgress,
  Box,
  InputAdornment,
  Collapse,
} from '@mui/material';
import SendIcon from '@mui/icons-material/Send';
import ReplayIcon from '@mui/icons-material/Replay';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { transferApi } from '../api/transferApi';
import { parseInrToMinorUnits } from '../../shared/utils/money';
import { TransferResponse } from '../types/transfer.types';
import { transferFeedback } from '../utils/transferFeedback';

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

export const TransferForm: React.FC = () => {
  const queryClient = useQueryClient();

  const [destinationId, setDestinationId] = useState('');
  const [amountInr, setAmountInr] = useState('');
  const [clientError, setClientError] = useState<{ field: 'destination' | 'amount'; message: string } | null>(null);
  const [lastSuccess, setLastSuccess] = useState<TransferResponse | null>(null);
  const submitting = useRef(false);
  const destinationInput = useRef<HTMLInputElement>(null);
  const amountInput = useRef<HTMLInputElement>(null);

  // Logical retry idempotency tracking
  const idempotencyRef = useRef<{
    key: string;
    boundDestination: string;
    boundAmount: string;
  }>({
    key: generateUUID(),
    boundDestination: '',
    boundAmount: '',
  });

  // Whenever user alters inputs, check if logical payload changed; if so, regenerate idempotency key
  const handleDestinationChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value.trim();
    setDestinationId(val);
    setClientError(null);
    setLastSuccess(null);

    transferMutation.reset();

    if (val !== idempotencyRef.current.boundDestination) {
      idempotencyRef.current = {
        key: generateUUID(),
        boundDestination: val,
        boundAmount: amountInr.trim(),
      };
    }
  };

  const handleAmountChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setAmountInr(val);
    setClientError(null);
    setLastSuccess(null);
    transferMutation.reset();

    if (val.trim() !== idempotencyRef.current.boundAmount) {
      idempotencyRef.current = {
        key: generateUUID(),
        boundDestination: destinationId.trim(),
        boundAmount: val.trim(),
      };
    }
  };

  const transferMutation = useMutation({
    mutationFn: async ({
      destId,
      minorUnits,
      key,
    }: {
      destId: string;
      minorUnits: number;
      key: string;
    }) => {
      return transferApi.createTransfer(
        {
          destinationLedgerAccountId: destId,
          amountMinor: minorUnits,
        },
        key
      );
    },
    retry: false, // Strict: no automatic blind HTTP retries for financial writes
    onSuccess: (data) => {
      setLastSuccess(data);
      setDestinationId('');
      setAmountInr('');
      // Prepare a fresh key for next new logical transfer
      idempotencyRef.current = {
        key: generateUUID(),
        boundDestination: '',
        boundAmount: '',
      };
      // Invalidate queries so balance and transfer history update from authoritative server state
      queryClient.invalidateQueries({ queryKey: ['wallet'] });
      queryClient.invalidateQueries({ queryKey: ['transfers'] });
    },
    onSettled: () => { submitting.current = false; },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting.current) return;
    setClientError(null);
    setLastSuccess(null);

    const dest = destinationId.trim();
    if (!dest) {
      setClientError({ field: 'destination', message: 'Recipient wallet ID is required.' });
      destinationInput.current?.focus();
      return;
    }

    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (!uuidRegex.test(dest)) {
      setClientError({ field: 'destination', message: 'Enter a valid 36-character wallet ID.' });
      destinationInput.current?.focus();
      return;
    }

    const parseResult = parseInrToMinorUnits(amountInr);
    if (!parseResult.ok || parseResult.minorUnits === undefined) {
      setClientError({ field: 'amount', message: parseResult.error || 'Enter a valid amount.' });
      amountInput.current?.focus();
      return;
    }

    // Ensure idempotency tracking is bound to this exact payload
    idempotencyRef.current.boundDestination = dest;
    idempotencyRef.current.boundAmount = amountInr.trim();

    submitting.current = true;
    transferMutation.mutate({
      destId: dest,
      minorUnits: parseResult.minorUnits,
      key: idempotencyRef.current.key,
    });
  };

  const feedback = transferMutation.isError ? transferFeedback(transferMutation.error) : null;

  return (
    <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, height: '100%' }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 0.5 }}>
          Send money
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5 }}>
          Send INR to another Customer wallet.
        </Typography>

        <Collapse in={!!lastSuccess}>
          {lastSuccess && (
            <Alert
              severity="success"
              icon={<CheckCircleIcon fontSize="inherit" />}
              sx={{ mb: 2.5 }}
              onClose={() => setLastSuccess(null)}
            >
              <AlertTitle sx={{ fontWeight: 700 }}>
                {lastSuccess.replayed ? 'Transfer already completed' : 'Transfer completed'}
              </AlertTitle>
              {lastSuccess.replayed && 'Your original transfer is confirmed. No additional funds were moved. '}
              Transfer ID: <code style={{ wordBreak: 'break-all' }}>{lastSuccess.transferId}</code>
            </Alert>
          )}
        </Collapse>

        <Collapse in={transferMutation.isError}>
          {feedback && (
            <Alert
              severity={feedback.severity}
              sx={{ mb: 2.5 }}
              action={
                feedback.payMerchantLink ? (
                  <Button
                    color="inherit"
                    size="small"
                    component={RouterLink}
                    to="/app/payments"
                  >
                    Pay merchant
                  </Button>
                ) : feedback.canRetry ? (
                  <Button
                    color="inherit"
                    size="small"
                    startIcon={<ReplayIcon />}
                    onClick={handleSubmit}
                    disabled={transferMutation.isPending}
                  >
                    Retry
                  </Button>
                ) : undefined
              }
            >
              <AlertTitle sx={{ fontWeight: 700 }}>
                {feedback.title}
              </AlertTitle>
              {feedback.message}
            </Alert>
          )}
        </Collapse>

        <Box component="form" onSubmit={handleSubmit} noValidate aria-busy={transferMutation.isPending}>
          <Stack spacing={2.5}>
            <TextField
              id="transfer-destination"
              inputRef={destinationInput}
              label="Recipient wallet ID"
              error={clientError?.field === 'destination'}
              value={destinationId}
              onChange={handleDestinationChange}
              fullWidth
              required
              disabled={transferMutation.isPending}
              helperText={clientError?.field === 'destination' ? clientError.message : 'Enter the Customer wallet ID you want to send money to.'}
              slotProps={{
                input: {
                  sx: { fontFamily: 'monospace', fontSize: '0.9rem' },
                },
              }}
            />

            <TextField
              id="transfer-amount"
              inputRef={amountInput}
              label="Amount (INR)"
              error={clientError?.field === 'amount'}
              placeholder="e.g. 100.00"
              value={amountInr}
              onChange={handleAmountChange}
              fullWidth
              required
              disabled={transferMutation.isPending}
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
                disabled={transferMutation.isPending || !destinationId.trim() || !amountInr.trim()}
                startIcon={
                  transferMutation.isPending ? (
                    <CircularProgress size={20} color="inherit" aria-hidden="true" />
                  ) : (
                    <SendIcon />
                  )
                }
                sx={{ px: 4, py: 1.2, fontWeight: 700, width: { xs: '100%', sm: 'auto' } }}
              >
                {transferMutation.isPending ? 'Sending money…' : 'Send money'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  );
};
