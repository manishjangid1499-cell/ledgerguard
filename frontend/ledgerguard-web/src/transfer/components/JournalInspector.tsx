import { Alert, Box, Card, CardContent, Chip, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import { JournalDetail } from '../types/transfer.types';
import { formatMinorUnitsToInr } from '../../shared/utils/money';
import { formatDateTime } from '../../shared/utils/display';
import { CopyButton } from '../../shared/components/CopyButton';
import { StatusBadge } from '../../shared/components/StatusBadge';

interface JournalInspectorProps {
  journal: JournalDetail;
  sourceLedgerAccountId: string;
  destinationLedgerAccountId: string;
}

export const JournalInspector = ({ journal, sourceLedgerAccountId, destinationLedgerAccountId }: JournalInspectorProps) => {
  // Display totals use exact minor-unit arithmetic; the server remains authoritative.
  let totalDebitsMinor = 0n;
  let totalCreditsMinor = 0n;
  let readable = true;
  for (const entry of journal.entries) {
    try {
      const amount = BigInt(entry.amountMinor);
      if (entry.direction === 'DEBIT') totalDebitsMinor += amount;
      else if (entry.direction === 'CREDIT') totalCreditsMinor += amount;
      else readable = false;
    } catch {
      readable = false;
    }
  }
  const isBalanced = readable && totalDebitsMinor > 0n && totalDebitsMinor === totalCreditsMinor;

  return (
    <Card>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ justifyContent: 'space-between', mb: 2.5 }}>
          <Box>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 0.5 }}>
              <LockOutlinedIcon color="secondary" fontSize="small" /><Typography component="h2" variant="h6">Journal record</Typography>
            </Stack>
            <Typography variant="body2" color="text.secondary">Immutable debit and credit entries for this transfer.</Typography>
          </Box>
          <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <StatusBadge status={journal.status} />
            {isBalanced && <Chip label="Balanced" color="success" size="small" variant="outlined" />}
          </Stack>
        </Stack>
        <Box sx={{ p: 1.5, mb: 2, bgcolor: 'background.default', borderRadius: 1 }}>
          <Typography variant="caption" color="text.secondary">Journal transaction ID</Typography>
          <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
            <Typography variant="body2" sx={{ fontFamily: 'monospace', overflowWrap: 'anywhere', minWidth: 0 }}>{journal.journalTransactionId}</Typography>
            <CopyButton value={journal.journalTransactionId} label="journal transaction ID" />
          </Stack>
        </Box>
        {!readable && <Alert severity="warning" sx={{ mb: 2 }}>Some journal amounts could not be displayed. Totals are unavailable.</Alert>}
        <Typography variant="caption" color="text.secondary" sx={{ display: { xs: 'block', md: 'none' }, mb: 1 }}>Scroll the table to view all journal columns.</Typography>
        <TableContainer tabIndex={0} role="region" aria-label="Journal entries, scroll horizontally for more columns"
          sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
          <Table size="small" sx={{ minWidth: 740 }} aria-label="Transfer journal entries">
            <TableHead sx={{ bgcolor: 'background.default' }}>
              <TableRow>
                <TableCell>Ledger account</TableCell><TableCell>Account</TableCell>
                <TableCell align="right">Debit (INR)</TableCell><TableCell align="right">Credit (INR)</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {journal.entries.map((entry, index) => (
                <TableRow key={index}>
                  <TableCell>
                    <Stack direction="row" sx={{ alignItems: 'center' }}>
                      <Typography variant="body2" sx={{ fontFamily: 'monospace', fontSize: '0.8rem' }}>{entry.ledgerAccountId}</Typography>
                      <CopyButton value={entry.ledgerAccountId} label="ledger account ID" />
                    </Stack>
                  </TableCell>
                  <TableCell sx={{ color: 'text.secondary', fontSize: '0.8rem' }}>
                    {entry.ledgerAccountId === sourceLedgerAccountId ? 'Source wallet' : entry.ledgerAccountId === destinationLedgerAccountId ? 'Recipient wallet' : 'Ledger account'}
                  </TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                    {entry.direction === 'DEBIT' ? formatMinorUnitsToInr(entry.amountMinor) : '—'}
                  </TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                    {entry.direction === 'CREDIT' ? formatMinorUnitsToInr(entry.amountMinor) : '—'}
                  </TableCell>
                </TableRow>
              ))}
              <TableRow sx={{ bgcolor: 'background.default' }}>
                <TableCell colSpan={2} sx={{ fontWeight: 700 }}>Total</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>{readable ? formatMinorUnitsToInr(totalDebitsMinor) : '—'}</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>{readable ? formatMinorUnitsToInr(totalCreditsMinor) : '—'}</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </TableContainer>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
          Posted {journal.postedAt ? formatDateTime(journal.postedAt) : '—'}
        </Typography>
      </CardContent>
    </Card>
  );
};
