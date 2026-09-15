import { Component, ReactNode } from 'react';
import { Alert, Button, Container, Stack, Typography } from '@mui/material';

export class PageErrorBoundary extends Component<{ children: ReactNode }, { hasError: boolean }> {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  render() {
    if (!this.state.hasError) return this.props.children;
    return (
      <Container maxWidth="md" sx={{ py: 3 }}>
        <Stack spacing={2} sx={{ alignItems: 'flex-start' }}>
          <Typography component="h1" variant="h5">Page unavailable</Typography>
          <Alert severity="error">This page could not be displayed. Reload it to try again.</Alert>
          <Button variant="outlined" onClick={() => window.location.reload()}>Reload page</Button>
        </Stack>
      </Container>
    );
  }
}
