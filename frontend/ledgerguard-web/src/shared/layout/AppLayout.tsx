import { Suspense, useState } from 'react';
import { AppBar, Box, Button, Chip, Container, IconButton, ListItemIcon, ListItemText, Menu, MenuItem, Stack, Toolbar, Typography } from '@mui/material';
import { Link as RouterLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import AccountCircleIcon from '@mui/icons-material/AccountCircle';
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined';
import ReceiptLongOutlinedIcon from '@mui/icons-material/ReceiptLongOutlined';
import PersonOutlineIcon from '@mui/icons-material/Person';
import ScienceOutlinedIcon from '@mui/icons-material/ScienceOutlined';
import LogoutIcon from '@mui/icons-material/Logout';
import { useAuth } from '../../auth/hooks/useAuth';
import { BrandLogo } from '../components/BrandLogo';
import { DataLoading } from '../components/DataLoading';
import { PageErrorBoundary } from '../components/PageErrorBoundary';
import { formatRoleLabel } from '../utils/display';

export const AppLayout = () => {
  const { user, logout } = useAuth();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [isSigningOut, setIsSigningOut] = useState(false);
  const displayName = user?.fullName?.trim() || user?.email || '';

  const links = [
    { to: '/app', label: 'Dashboard', icon: DashboardOutlinedIcon, active: pathname === '/app' },
    ...(user?.role === 'CUSTOMER' || user?.role === 'MERCHANT' ? [{ to: user?.role === 'MERCHANT' ? '/app/payments' : '/app/activity', label: 'Activity', icon: ReceiptLongOutlinedIcon,
      active: ['/app/activity', '/app/funding', '/app/payments', '/app/payouts', '/app/transfers/'].some(path => pathname.startsWith(path)) }] : []),
    { to: '/profile', label: 'Profile', icon: PersonOutlineIcon, active: pathname === '/profile' },
    ...(user?.role === 'OPS' ? [{ to: '/app/failure-lab', label: 'Failure Lab', icon: ScienceOutlinedIcon, active: pathname.startsWith('/app/failure-lab') }] : []),
  ];

  const navigation = (
    <Stack component="nav" aria-label="Main navigation" direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
      {links.map(({ to, label, icon: Icon, active }) => (
        <Button key={to} component={RouterLink} to={to} size="small" startIcon={<Icon />}
          aria-current={active ? 'page' : undefined}
          sx={{ px: 1.5, color: active ? 'primary.main' : 'text.secondary', bgcolor: active ? 'action.selected' : 'transparent' }}>
          {label}
        </Button>
      ))}
    </Stack>
  );

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <a className="skip-link" href="#main-content">Skip to content</a>
      <AppBar position="sticky" color="transparent" elevation={0}
        sx={{ borderBottom: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
        <Container maxWidth="lg">
          <Toolbar disableGutters sx={{ justifyContent: 'space-between', gap: 2 }}>
            <Stack direction="row" spacing={3} sx={{ alignItems: 'center', minWidth: 0 }}>
              <RouterLink to="/app" aria-label="LedgerGuard dashboard" style={{ textDecoration: 'none' }}><BrandLogo size="small" /></RouterLink>
              <Box sx={{ display: { xs: 'none', md: 'block' } }}>{navigation}</Box>
            </Stack>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', minWidth: 0 }}>
              {user && <Chip label={formatRoleLabel(user.role)} size="small" variant="outlined" sx={{ fontWeight: 600 }} />}
              {user && displayName && <Typography variant="body2" color="text.secondary" noWrap title={displayName}
                sx={{ display: { xs: 'none', lg: 'block' }, maxWidth: 220 }}>{displayName}</Typography>}
              <IconButton id="account-menu-button" aria-label="Account options" aria-haspopup="menu"
                aria-expanded={Boolean(anchor)} aria-controls={anchor ? 'account-menu' : undefined}
                onClick={event => setAnchor(event.currentTarget)}>
                <AccountCircleIcon />
              </IconButton>
              <Menu id="account-menu" anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}
                transformOrigin={{ horizontal: 'right', vertical: 'top' }} anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}
                slotProps={{ list: { 'aria-labelledby': 'account-menu-button' }, paper: { sx: { minWidth: 200, maxWidth: 'calc(100vw - 32px)' } } }}>
                {links.map(({ to, label, icon: Icon, active }) => (
                  <MenuItem key={to} component={RouterLink} to={to} selected={active} onClick={() => setAnchor(null)}>
                    <ListItemIcon><Icon fontSize="small" /></ListItemIcon><ListItemText>{label}</ListItemText>
                  </MenuItem>
                ))}
                <MenuItem
                  disabled={isSigningOut}
                  onClick={async () => {
                    if (isSigningOut) return;
                    setIsSigningOut(true);
                    setAnchor(null);
                    try {
                      await logout();
                    } finally {
                      setIsSigningOut(false);
                      navigate('/login', { replace: true });
                    }
                  }}
                >
                  <ListItemIcon><LogoutIcon fontSize="small" /></ListItemIcon>
                  <ListItemText>{isSigningOut ? 'Signing out...' : 'Sign out'}</ListItemText>
                </MenuItem>
              </Menu>
            </Stack>
          </Toolbar>
          <Box sx={{ display: { xs: 'block', md: 'none' }, pb: 1 }}>{navigation}</Box>
        </Container>
      </AppBar>
      <Box component="main" id="main-content" tabIndex={-1} sx={{ flexGrow: 1, py: { xs: 3, md: 4 }, outline: 'none', minWidth: 0 }}>
        <PageErrorBoundary key={pathname}>
          <Suspense fallback={<Container maxWidth="lg"><DataLoading label="Loading page" minHeight={400} /></Container>}>
            <Outlet />
          </Suspense>
        </PageErrorBoundary>
      </Box>
    </Box>
  );
};
