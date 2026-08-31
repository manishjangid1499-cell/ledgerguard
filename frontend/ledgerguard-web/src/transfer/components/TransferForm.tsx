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
import { transferApi } from '../api/transferApi';
import { parseInrToMinorUnits } from '../../shared/utils/money';
import { ApiError } from '../../shared/types/api.types';
import { TransferResponse } from '../types/transfer.types';

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
  const [clientError, setClientError] = useState<string | null>(null);
  const [lastSuccess, setLastSuccess] = useState<TransferResponse | null>(null);

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
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setClientError(null);
    setLastSuccess(null);

    const dest = destinationId.trim();
    if (!dest) {
      setClientError('Destination wallet ID is required');
      return;
    }

    const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (!uuidRegex.test(dest)) {
      setClientError('Destination wallet ID must be a valid UUID');
      return;
    }

    const parseResult = parseInrToMinorUnits(amountInr);
    if (!parseResult.ok || parseResult.minorUnits === undefined) {
      setClientError(parseResult.error || 'Invalid amount');
      return;
    }

    // Ensure idempotency tracking is bound to this exact payload
    idempotencyRef.current.boundDestination = dest;
    idempotencyRef.current.boundAmount = amountInr.trim();

    transferMutation.mutate({
      destId: dest,
      minorUnits: parseResult.minorUnits,
      key: idempotencyRef.current.key,
    });
  };

  const getErrorMessage = (error: unknown): string => {
    if (error instanceof ApiError) {
      if (error.problem.errorCode === 'INSUFFICIENT_FUNDS') {
        return 'Insufficient funds for this transfer.';
      }
      if (error.problem.errorCode === 'RESOURCE_NOT_FOUND') {
        return 'Destination wallet account not found.';
      }
      if (error.problem.status === 0) {
        return "We couldn't confirm the transfer result. You can safely retry without risking double-deduction.";
      }
      return error.problem.detail || 'Transfer failed. Please check details and try again.';
    }
    if (error instanceof Error) {
      return error.message;
    }
    return 'Transfer failed. Please try again.';
  };

  const isNetworkError =
    transferMutation.isError &&
    transferMutation.error instanceof ApiError &&
    transferMutation.error.problem.status === 0;

  return (
    <Card elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 2 }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography variant="h6" sx={{ fontWeight: 700, mb: 0.5 }}>
          Send Internal Transfer
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2.5 }}>
          Transfer INR funds instantly to another LedgerGuard wallet account.
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
                Transfer Completed {lastSuccess.replayed ? '(Idempotent Replay)' : 'Successfully'}
              </AlertTitle>
              Transfer ID: <code style={{ wordBreak: 'break-all' }}>{lastSuccess.transferId}</code>
            </Alert>
          )}
        </Collapse>

        <Collapse in={!!clientError}>
          {clientError && (
            <Alert severity="error" sx={{ mb: 2.5 }} onClose={() => setClientError(null)}>
              {clientError}
            </Alert>
          )}
        </Collapse>

        <Collapse in={transferMutation.isError}>
          {transferMutation.isError && (
            <Alert
              severity={isNetworkError ? 'warning' : 'error'}
              sx={{ mb: 2.5 }}
              action={
                isNetworkError && (
                  <Button
                    color="inherit"
                    size="small"
                    startIcon={<ReplayIcon />}
                    onClick={handleSubmit}
                    disabled={transferMutation.isPending}
                  >
                    Retry Safely
                  </Button>
                )
              }
            >
              <AlertTitle sx={{ fontWeight: 700 }}>
                {isNetworkError ? 'Connection Issue' : 'Transfer Rejected'}
              </AlertTitle>
              {getErrorMessage(transferMutation.error)}
            </Alert>
          )}
        </Collapse>

        <Box component="form" onSubmit={handleSubmit} noValidate>
          <Stack spacing={2.5}>
            <TextField
              label="Destination Wallet ID"
              placeholder="e.g. 550e8400-e29b-41d4-a716-446655440000"
              value={destinationId}
              onChange={handleDestinationChange}
              fullWidth
              required
              disabled={transferMutation.isPending}
              helperText="Enter the recipient's 36-character ledger account UUID"
              slotProps={{
                input: {
                  sx: { fontFamily: 'monospace', fontSize: '0.9rem' },
                },
              }}
            />

            <TextField
              label="Amount (INR)"
              placeholder="e.g. 100.00"
              value={amountInr}
              onChange={handleAmountChange}
              fullWidth
              required
              disabled={transferMutation.isPending}
              helperText="Positive amount with up to 2 decimal places"
              slotProps={{
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
                    <CircularProgress size={20} color="inherit" />
                  ) : (
                    <SendIcon />
                  )
                }
                sx={{ px: 4, py: 1.2, fontWeight: 700 }}
              >
                {transferMutation.isPending ? 'Executing Transfer...' : 'Send Transfer'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  );
};
