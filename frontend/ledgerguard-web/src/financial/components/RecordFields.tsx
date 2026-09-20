import { Box, Stack, Typography } from '@mui/material';
import { CopyButton } from '../../shared/components/CopyButton';

export const RecordFields = ({ fields }: { fields: { label: string; value: string | null; copy?: boolean }[] }) => (
  <Box component="dl" sx={{ m: 0 }}>
    {fields.map(({ label, value, copy }) => <Box key={label} sx={{ mt: 2 }}>
      <Typography component="dt" variant="caption" color="text.secondary">{label}</Typography>
      <Stack component="dd" direction="row" spacing={0.5} sx={{ m: 0, alignItems: 'center' }}>
        <Typography component="span" variant="body2" sx={{ minWidth: 0, overflowWrap: 'anywhere', ...(copy ? { fontFamily: 'monospace' } : {}) }}>{value || 'Not available yet'}</Typography>
        {copy && value && <CopyButton value={value} label={label.toLowerCase()} />}
      </Stack>
    </Box>)}
  </Box>
);
