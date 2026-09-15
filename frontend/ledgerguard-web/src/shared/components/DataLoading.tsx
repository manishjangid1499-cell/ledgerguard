import { Box, Skeleton, Stack } from '@mui/material';

export const DataLoading = ({ label, minHeight = 180 }: { label: string; minHeight?: number }) => (
  <Box role="status" aria-label={label} sx={{ minHeight, py: 1 }}>
    <span className="sr-only">{label}</span>
    <Stack spacing={1.5} aria-hidden="true">
      <Skeleton width="45%" height={32} />
      <Skeleton variant="rounded" height={52} />
      <Skeleton variant="rounded" height={52} />
    </Stack>
  </Box>
);
