import { Box, Grid, Stack, Typography } from '@mui/material';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';

export const PlatformArchitecture = () => (
  <Box component="section" id="architecture" aria-labelledby="architecture-heading" sx={{ mb: { xs: 6, md: 7 } }}>
    <Typography variant="overline" color="secondary.main" sx={{ fontWeight: 700 }}>Architecture &amp; reliability</Typography>
    <Typography id="architecture-heading" component="h2" variant="h5" color="primary.main" sx={{ mt: 0.75, mb: 1 }}>Committed together. Reconciled independently.</Typography>
    <Typography variant="body2" color="text.secondary" sx={{ mb: 3, maxWidth: 760, lineHeight: 1.7 }}>
      Ledger postings and event records commit in one database transaction. Provider calls cross a separate boundary, with confirmed outcomes reconciled against internal records.
    </Typography>
    <Grid container spacing={2}>
      <Grid size={{ xs: 12, md: 6 }}>
        <Box sx={{ height: '100%', p: 3, bgcolor: 'primary.main', color: 'primary.contrastText', borderRadius: 2 }}>
          <Typography component="h3" variant="subtitle2" sx={{ color: '#a6ddd3', mb: 2 }}>Financial record</Typography>
          <Stack direction="row" spacing={1.5} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <Typography variant="body2">Client</Typography><ArrowForwardIcon aria-hidden="true" fontSize="small" />
            <Typography variant="body2" sx={{ fontWeight: 700 }}>LedgerGuard API</Typography>
            <ArrowForwardIcon aria-hidden="true" fontSize="small" />
            <Typography variant="body2" sx={{ fontWeight: 700 }}>PostgreSQL ledger</Typography>
          </Stack>
          <Typography variant="body2" sx={{ mt: 2, color: '#d6e1eb', lineHeight: 1.7 }}>Immutable journal · Derived balance snapshots · Transactional outbox</Typography>
        </Box>
      </Grid>
      <Grid size={{ xs: 12, md: 6 }}>
        <Stack spacing={2} sx={{ height: '100%' }}>
          {[
            ['Event delivery', 'Outbox → Kafka → Notification worker'],
            ['Provider boundary', 'LedgerGuard API ↔ PSP simulator', 'Provider outcomes inform settlement and reconciliation.'],
          ].map(([title, flow, detail]) => (
            <Box key={title} sx={{ flex: 1, px: 2.5, py: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', borderRadius: 2 }}>
              <Typography component="h3" variant="caption" color="text.secondary" sx={{ fontWeight: 600 }}>{title}</Typography>
              <Typography variant="body2" sx={{ mt: 0.5, fontWeight: 600 }}>{flow}</Typography>
              {detail && <Typography variant="caption" color="text.secondary">{detail}</Typography>}
            </Box>
          ))}
        </Stack>
      </Grid>
    </Grid>
  </Box>
);
