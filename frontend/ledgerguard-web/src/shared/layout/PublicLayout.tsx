import React from 'react';
import { Box, AppBar, Toolbar, Container, Button, Stack } from '@mui/material';
import { Link as RouterLink, Outlet } from 'react-router-dom';
import { BrandLogo } from '../components/BrandLogo';

export const PublicLayout: React.FC = () => {
  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', bgcolor: 'background.default' }}>
      <AppBar
        position="static"
        color="transparent"
        elevation={0}
        sx={{ borderBottom: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}
      >
        <Container maxWidth="lg">
          <Toolbar disableGutters sx={{ justifyContent: 'space-between' }}>
            <RouterLink to="/" style={{ textDecoration: 'none', color: 'inherit' }}>
              <BrandLogo size="small" />
            </RouterLink>
            <Stack direction="row" spacing={1.5}>
              <Button component={RouterLink} to="/login" variant="outlined" color="primary" size="small">
                Sign in
              </Button>
              <Button component={RouterLink} to="/register" variant="contained" color="primary" size="small">
                Create account
              </Button>
            </Stack>
          </Toolbar>
        </Container>
      </AppBar>
      <Box component="main" sx={{ flexGrow: 1, py: { xs: 4, md: 6 } }}>
        <Outlet />
      </Box>
    </Box>
  );
};
