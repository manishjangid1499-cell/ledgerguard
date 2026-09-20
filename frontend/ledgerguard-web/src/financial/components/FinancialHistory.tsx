import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Card, CardContent, IconButton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, Typography } from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Link as RouterLink } from 'react-router-dom';
import { financialApi } from '../api';
import { FinancialDomain, Page } from '../types';
import { financialError } from '../feedback';
import { DataLoading } from '../../shared/components/DataLoading';
import { StatusBadge } from '../../shared/components/StatusBadge';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';

interface HistoryRow { id: string; amount: string; fee?: string; net?: string; status: string; createdAt: string }
async function history(domain: FinancialDomain, page: number): Promise<Page<HistoryRow>> {
  if (domain === 'payments') {
    const result = await financialApi.payments(page);
    return { ...result, items: result.items.map(item => ({ id: item.paymentId, amount: item.grossAmountMinor,
      fee: item.feeAmountMinor, net: item.merchantNetAmountMinor, status: item.status, createdAt: item.createdAt })) };
  }
  const result = domain === 'funding' ? await financialApi.funding(page) : await financialApi.payouts(page);
  return { ...result, items: result.items.map(item => ({ id: 'fundingId' in item ? item.fundingId : item.payoutId,
    amount: item.amountMinor, status: item.status, createdAt: item.createdAt })) };
}
export const FinancialHistory = ({ domain, merchant = false }: { domain: FinancialDomain; merchant?: boolean }) => {
  const [page, setPage] = useState(0);
  const query = useQuery({ queryKey: [domain, 'list', page], queryFn: () => history(domain, page), staleTime: 15_000 });
  const title = domain === 'payments' ? (merchant ? 'Customer payments' : 'Merchant payments') : domain === 'funding' ? 'Funding history' : 'Payout history';
  const subtitle = domain === 'payments' && merchant ? 'Payments customers make to your business using Pay merchant.' : undefined;
  const empty = domain === 'payments' ? (merchant ? 'No customer payments yet.' : 'No payments yet.') : domain === 'funding' ? 'No funding activity yet.' : 'No payouts yet.';
  const payments = domain === 'payments';
  const data = query.data;
  return <Card><CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
    <Stack direction="row" spacing={1} sx={{ justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
      <Box>
        <Typography component="h2" variant="h6" sx={{ mb: subtitle ? 0.5 : 0 }}>{title}</Typography>
        {subtitle && <Typography variant="body2" color="text.secondary">{subtitle}</Typography>}
      </Box>
      <IconButton aria-label={`Refresh ${title.toLowerCase()}`} disabled={query.isFetching} onClick={() => { void query.refetch(); }}><RefreshIcon /></IconButton>
    </Stack>
    {query.isLoading ? <DataLoading label={`Loading ${title.toLowerCase()}`} /> : query.isError ?
      <Alert severity="error">{financialError(query.error, `Unable to load ${title.toLowerCase()}. Please try again.`)}</Alert> : !data ?
      <Alert severity="error">These records are unavailable. Please try again.</Alert> : !data.items.length ? (
        merchant && payments ? (
          <Box sx={{ py: 4, textAlign: 'center' }}>
            <Typography variant="body2" color="text.secondary">No customer payments yet.</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
              Payments made with Pay merchant will appear here.
            </Typography>
          </Box>
        ) : (
          <Typography variant="body2" color="text.secondary" sx={{ py: 4, textAlign: 'center' }}>{empty}</Typography>
        )
      ) : <>
        <Box sx={{ display: { xs: 'block', md: 'none' } }}>
          {data.items.map(row => <Box key={row.id} sx={{ py: 2, borderTop: '1px solid', borderColor: 'divider' }}>
            <Stack direction="row" useFlexGap spacing={1} sx={{ flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'center' }}>
              <Typography variant="body2" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', overflowWrap: 'anywhere' }}>{formatMinorUnitsToInr(row.amount)}</Typography>
              <StatusBadge status={row.status} />
            </Stack>
            {payments && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
              Fee {formatMinorUnitsToInr(row.fee)} · Merchant net {formatMinorUnitsToInr(row.net)}
            </Typography>}
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>{formatDateTime(row.createdAt)}</Typography>
            <Typography variant="caption" sx={{ fontFamily: 'monospace', overflowWrap: 'anywhere', display: 'block', my: 1 }}>{row.id}</Typography>
            <Button component={RouterLink} to={`/app/${domain}/${row.id}`} size="small" aria-label={`View ${domain} record ${row.id}`}>View details</Button>
          </Box>)}
        </Box>
        <TableContainer tabIndex={0} role="region" aria-label={`${title}, scroll horizontally for more columns`} sx={{ display: { xs: 'none', md: 'block' } }}>
          <Table aria-label={title} sx={{ minWidth: payments ? 920 : 680 }}>
            <TableHead><TableRow><TableCell>ID</TableCell><TableCell align="right">{payments ? 'Gross' : 'Amount'}</TableCell>
              {payments && <><TableCell align="right">Platform fee</TableCell><TableCell align="right">Merchant net</TableCell></>}
              <TableCell>Status</TableCell><TableCell>Date</TableCell><TableCell>Details</TableCell></TableRow></TableHead>
            <TableBody>{data.items.map(row => <TableRow key={row.id}>
              <TableCell><Typography variant="caption" title={row.id} sx={{ fontFamily: 'monospace' }}>{row.id.slice(0, 8)}…{row.id.slice(-4)}</Typography></TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{formatMinorUnitsToInr(row.amount)}</TableCell>
              {payments && <><TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatMinorUnitsToInr(row.fee)}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatMinorUnitsToInr(row.net)}</TableCell></>}
              <TableCell><StatusBadge status={row.status} /></TableCell><TableCell>{formatDateTime(row.createdAt)}</TableCell>
              <TableCell><Button component={RouterLink} to={`/app/${domain}/${row.id}`} size="small" aria-label={`View ${domain} record ${row.id}`}>View details</Button></TableCell>
            </TableRow>)}</TableBody>
          </Table>
        </TableContainer>
      </>}
    {data && !query.isError && data.totalElements > 0 && <TablePagination component="div" count={data.totalElements}
      page={data.page} rowsPerPage={10} rowsPerPageOptions={[10]} onPageChange={(_, next) => setPage(next)}
      sx={{ '.MuiTablePagination-toolbar': { px: 0 }, '.MuiTablePagination-actions': { ml: 1 } }} />}
  </CardContent></Card>;
};
