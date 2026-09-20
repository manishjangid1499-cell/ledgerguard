import { FormEvent, useRef, useState } from 'react';
import { Alert, Box, Button, CircularProgress, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import { parseInrToMinorUnits, formatMinorUnitsToInr } from '../../shared/utils/money';
import { useFinancialSubmission } from '../hooks';
import { financialError } from '../feedback';
import { FinancialDomain } from '../types';

interface MoneySubmission { amountMinor: number; merchantLedgerAccountId: string }
interface Props<R> {
  domain: FinancialDomain; label: string; merchant?: boolean; refundableAmountMinor?: string;
  submit: (payload: MoneySubmission, key: string) => Promise<R>; onSuccess: (result: R) => void;
}
export function FinancialForm<R>({ domain, label, merchant = false, refundableAmountMinor, submit, onSuccess }: Props<R>) {
  const [amount, setAmount] = useState('');
  const [merchantId, setMerchantId] = useState('');
  const [fieldError, setFieldError] = useState<{ field: 'amount' | 'merchant'; message: string } | null>(null);
  const amountInput = useRef<HTMLInputElement>(null);
  const merchantInput = useRef<HTMLInputElement>(null);
  const mutation = useFinancialSubmission(domain, submit, onSuccess);
  const locked = mutation.isPending || mutation.uncertain || mutation.isSuccess;
  const send = (event: FormEvent) => {
    event.preventDefault(); setFieldError(null);
    const walletId = merchantId.trim();
    if (merchant && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(walletId)) {
      setFieldError({ field: 'merchant', message: 'Enter the Merchant’s 36-character wallet ID.' });
      merchantInput.current?.focus(); return;
    }
    const parsed = parseInrToMinorUnits(amount);
    if (!parsed.ok || parsed.minorUnits === undefined) {
      setFieldError({ field: 'amount', message: parsed.error || 'Enter a valid amount.' });
      amountInput.current?.focus(); return;
    }
    mutation.execute({ amountMinor: parsed.minorUnits, merchantLedgerAccountId: walletId });
  };
  return <Box component="form" noValidate onSubmit={send} aria-busy={mutation.isPending}>
    <Stack spacing={2.5}>
      {mutation.isError && <Alert severity={mutation.uncertain ? 'warning' : 'error'}>
        {mutation.uncertain
          ? 'The result is not confirmed. Retry this same request below; its original details and request key are retained. Review your activity before starting a separate request.'
          : financialError(mutation.error, 'This request could not be completed. Review the details and try again.')}
      </Alert>}
      {merchant && <TextField id="payment-merchant" inputRef={merchantInput} label="Merchant wallet ID" required fullWidth
        value={merchantId} disabled={locked} error={fieldError?.field === 'merchant'}
        onChange={event => { setMerchantId(event.target.value); setFieldError(null); mutation.resetFeedback(); }}
        helperText={fieldError?.field === 'merchant' ? fieldError.message : 'Ask the Merchant for the wallet ID on their dashboard.'} />}
      <TextField id={`${domain}-amount`} inputRef={amountInput} label="Amount (INR)" required fullWidth value={amount}
        disabled={locked} error={fieldError?.field === 'amount'}
        onChange={event => { setAmount(event.target.value); setFieldError(null); mutation.resetFeedback(); }}
        helperText={fieldError?.field === 'amount' ? fieldError.message : 'Enter a positive amount with up to 2 decimal places.'}
        slotProps={{ htmlInput: { inputMode: 'decimal' }, input: { startAdornment: <InputAdornment position="start">₹</InputAdornment> } }} />
      {refundableAmountMinor !== undefined && <Box>
        <Typography variant="body2" color="text.secondary">Remaining refundable amount: {formatMinorUnitsToInr(refundableAmountMinor)}</Typography>
        <Button size="small" disabled={locked} sx={{ mt: 0.5 }} onClick={() => {
          // Formatting only; refund totals and fee allocation come from the API.
          const minor = BigInt(refundableAmountMinor);
          setAmount(`${minor / 100n}.${(minor % 100n).toString().padStart(2, '0')}`);
          setFieldError(null); mutation.resetFeedback();
        }}>Use full remaining amount</Button>
      </Box>}
      <Button type="submit" variant="contained" disabled={mutation.isPending || mutation.isSuccess}
        startIcon={mutation.isPending ? <CircularProgress size={18} color="inherit" aria-hidden="true" /> : undefined}
        sx={{ alignSelf: { xs: 'stretch', sm: 'flex-start' } }}>
        {mutation.isPending ? 'Submitting…' : mutation.uncertain ? 'Retry same request' : label}
      </Button>
    </Stack>
  </Box>;
}
