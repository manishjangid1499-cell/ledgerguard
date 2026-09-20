import { Alert, Box, Card, CardContent, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import AccountBalanceWalletOutlinedIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { walletApi } from '../api/walletApi';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { CopyButton } from '../../shared/components/CopyButton';
import { DataLoading } from '../../shared/components/DataLoading';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { getErrorMessage } from '../../shared/api/errorMessage';
import { ApiError } from '../../shared/types/api.types';
import { AmountDisplay } from '../../shared/components/AmountDisplay';

export const WalletCard = () => {
  const queryClient = useQueryClient();
  const { data: wallet, isLoading, isError, error, isFetching } = useQuery({
    queryKey: ['wallet'], queryFn: walletApi.getMyWallet, staleTime: 30_000,
  });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ['wallet'] }); };
  return (
    <Card sx={{ height: '100%', minHeight: 390 }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <AccountBalanceWalletOutlinedIcon color="secondary" />
            <Typography component="h2" variant="h6">Wallet balance</Typography>
          </Stack>
          <Tooltip title="Refresh balance">
            <span><IconButton aria-label="Refresh wallet balance" onClick={refresh} disabled={isFetching}><RefreshIcon fontSize="small" /></IconButton></span>
          </Tooltip>
        </Stack>
        {isLoading ? <DataLoading label="Loading wallet balance" minHeight={270} /> : isError || !wallet ? (
          <Alert severity="error">
            {error instanceof ApiError && error.status === 404 ? 'No wallet is available for this account.' :
              getErrorMessage(error, 'Unable to load your wallet balance. Please try again.')}
          </Alert>
        ) : (
          <>
            <Typography variant="body2" color="text.secondary">Available balance · {wallet.currency}</Typography>
            <AmountDisplay amount={wallet.availableBalanceMinor} />
            <StatusBadge status={wallet.status} />
            <Box sx={{ my: 2.5, py: 1, borderTop: '1px solid', borderBottom: '1px solid', borderColor: 'divider' }}>
              {[['Posted balance', wallet.balanceMinor], ['On hold', wallet.activeHoldAmountMinor]].map(([label, amount]) => (
                <Stack key={label} direction="row" useFlexGap spacing={1} sx={{ justifyContent: 'space-between', flexWrap: 'wrap', py: 1 }}>
                  <Typography variant="body2" color="text.secondary">{label}</Typography>
                  <Typography variant="body2" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums', overflowWrap: 'anywhere' }}>{formatMinorUnitsToInr(amount)}</Typography>
                </Stack>
              ))}
            </Box>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
              {wallet.accountType === 'MERCHANT' ? 'Merchant wallet ID' : 'Wallet ID · Share to receive transfers'}
            </Typography>
            {wallet.accountType === 'MERCHANT' && <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>Share this ID with a Customer to receive a LedgerGuard payment.</Typography>}
            <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', bgcolor: 'background.default', pl: 1.5, py: 0.5, borderRadius: 1 }}>
              <Typography variant="body2" sx={{ flex: 1, minWidth: 0, overflowWrap: 'anywhere', fontFamily: 'monospace', fontSize: '0.8rem' }}>{wallet.ledgerAccountId}</Typography>
              <CopyButton value={wallet.ledgerAccountId} label="wallet ID" />
            </Stack>
          </>
        )}
      </CardContent>
    </Card>
  );
};
