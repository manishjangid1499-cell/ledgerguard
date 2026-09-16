import { Box, Container, Link, Stack, Typography } from '@mui/material';
import { BrandLogo } from './BrandLogo';

export const PublicFooter = ({ compact = false }: { compact?: boolean }) => (
  <Box component="footer" sx={{ borderTop: compact ? 0 : '1px solid', borderColor: 'divider', py: 3 }}>
    <Container maxWidth="lg">
      {compact ? (
        <Typography variant="caption" color="text.secondary" align="center" component="p">
          LedgerGuard · Payment Integrity &amp; Ledger Platform
        </Typography>
      ) : (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}
          sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}>
          <Box>
            <BrandLogo size="small" />
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              Payment Integrity &amp; Ledger Platform
            </Typography>
          </Box>
          <Stack component="nav" aria-label="Resources" direction="row" spacing={3}>
            <Link href="https://github.com/manishjangid1499-cell/ledgerguard/blob/HEAD/README.md" variant="body2" underline="hover">Documentation</Link>
            <Link href="#how-it-works" variant="body2" underline="hover">How it works</Link>
            <Link href="https://github.com/manishjangid1499-cell/ledgerguard" variant="body2" underline="hover">GitHub</Link>
          </Stack>
        </Stack>
      )}
    </Container>
  </Box>
);
