import { AppBar, Box, Button, Container, Stack, Toolbar } from '@mui/material';
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../../auth/hooks/useAuth';
import { BrandLogo } from '../components/BrandLogo';
import { PublicFooter } from '../components/PublicFooter';

export const PublicLayout = () => {
  const { pathname } = useLocation();
  const { status } = useAuth();
  const isLanding = pathname === '/';

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <AppBar position="static" color="transparent" elevation={0}
        sx={{ borderBottom: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
        <Container maxWidth="lg">
          <Toolbar disableGutters sx={{ justifyContent: 'space-between', gap: 1, minHeight: 72 }}>
            <RouterLink to="/" aria-label="LedgerGuard home" style={{ textDecoration: 'none' }}>
              <BrandLogo size="small" />
            </RouterLink>
            {isLanding && (
              <Stack component="nav" aria-label="Product navigation" direction="row" spacing={1}
                sx={{ display: { xs: 'none', md: 'flex' } }}>
                <Button href="#product" color="inherit">Product</Button>
                <Button href="#how-it-works" color="inherit">How it works</Button>
                <Button href="#security" color="inherit">Security</Button>
              </Stack>
            )}
            <Stack component="nav" aria-label="Account" direction="row" spacing={1}>
              {status === 'authenticated' ? (
                <Button component={RouterLink} to="/app" variant="contained" size="small">Dashboard</Button>
              ) : (
                <>
                  <Button component={RouterLink} to="/login" variant={isLanding ? 'text' : 'outlined'}
                    size="small" sx={{ whiteSpace: 'nowrap', minWidth: 0, px: { xs: 1, sm: 2 } }}>Sign in</Button>
                  <Button component={RouterLink} to="/register" variant="contained" size="small"
                    sx={{ whiteSpace: 'nowrap', px: { xs: 1.25, sm: 2 } }}>Create account</Button>
                </>
              )}
            </Stack>
          </Toolbar>
        </Container>
      </AppBar>
      <Box component="main" id="main-content" tabIndex={-1}
        sx={{ flexGrow: 1, py: isLanding ? 0 : { xs: 4, md: 6 }, outline: 'none' }}>
        <Outlet />
      </Box>
      <PublicFooter compact={!isLanding} />
    </Box>
  );
};
