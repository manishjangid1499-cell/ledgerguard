import { Box, Card, CardContent, Stack, TablePagination, Typography } from '@mui/material';
import { Page, Refund } from '../types';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';
import { RecordFields } from './RecordFields';

export const RefundHistory = ({ refunds, onPageChange }: { refunds: Page<Refund>; onPageChange: (page: number) => void }) => (
  <Card><CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
    <Typography component="h2" variant="h6" sx={{ mb: 2 }}>Refunds</Typography>
    {!refunds.items.length ? <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>No refunds have been issued for this payment.</Typography>
      : refunds.items.map(refund => <Box key={refund.refundId} sx={{ py: 2, borderTop: '1px solid', borderColor: 'divider' }}>
        <Stack direction="row" useFlexGap spacing={1} sx={{ justifyContent: 'space-between', flexWrap: 'wrap' }}>
          <Typography sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{formatMinorUnitsToInr(refund.refundAmountMinor)}</Typography>
          <Typography variant="body2" color="text.secondary">{formatDateTime(refund.createdAt)}</Typography>
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Merchant debit {formatMinorUnitsToInr(refund.merchantDebitAmountMinor)} · Fee reversal {formatMinorUnitsToInr(refund.feeDebitAmountMinor)}
        </Typography>
        <RecordFields fields={[{ label: 'Refund ID', value: refund.refundId, copy: true }]} />
      </Box>)}
    {refunds.totalElements > 0 && <TablePagination component="div" count={refunds.totalElements} page={refunds.page}
      rowsPerPage={10} rowsPerPageOptions={[10]} onPageChange={(_, next) => onPageChange(next)}
      sx={{ '.MuiTablePagination-toolbar': { px: 0 }, '.MuiTablePagination-actions': { ml: 1 } }} />}
  </CardContent></Card>
);
