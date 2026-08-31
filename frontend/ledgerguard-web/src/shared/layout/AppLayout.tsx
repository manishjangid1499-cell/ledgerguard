import React from 'react';
import {
  Box,
  AppBar,
  Toolbar,
  Container,
  Button,
  Stack,
  Chip,
  Typography,
  IconButton,
  Menu,
  MenuItem,
  ListItemIcon,
  ListItemText,
} from '@mui/material';
import { Link as RouterLink, Outlet, useNavigate, useLocation } from 'react-router-dom';
import AccountCircleIcon from '@mui/icons-material/AccountCircle';
import LogoutIcon from '@mui/icons-material/Logout';
import DashboardIcon from '@mui/icons-material/Dashboard';
import PersonIcon from '@mui/icons-material/Person';
import { useAuth } from '../../auth/hooks/useAuth';
import { BrandLogo } from '../components/BrandLogo';
import { UserRole } from '../types/user.types';

function formatRoleLabel(role: UserRole): string {
  switch (role) {
    case 'CUSTOMER':
      return 'Customer';
    case 'MERCHANT':
      return 'Merchant';
    case 'OPS':
      return 'Operations';
    default:
      return role;
  }
}

function roleColor(role: UserRole): 'default' | 'primary' | 'secondary' | 'info' {
  switch (role) {
    case 'MERCHANT':
      return 'secondary';
    case 'OPS':
      return 'info';
    case 'CUSTOMER':
    default:
      return 'primary';
  }
}

export const AppLayout: React.FC = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [anchorEl, setAnchorEl] = React.useState<null | HTMLElement>(null);

  const handleMenuOpen = (event: React.MouseEvent<HTMLElement>) => {
    setAnchorEl(event.currentTarget);
  };

  const handleMenuClose = () => {
    setAnchorEl(null);
  };

  const handleLogout = async () => {
    handleMenuClose();
    await logout();
    navigate('/login');
  };

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', bgcolor: 'background.default' }}>
      <AppBar
        position="sticky"
        color="transparent"
        elevation={0}
        sx={{ borderBottom: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}
      >
        <Container maxWidth="lg">
          <Toolbar disableGutters sx={{ justifyContent: 'space-between' }}>
            <Stack direction="row" spacing={3} sx={{ alignItems: 'center' }}>
              <RouterLink to="/app" style={{ textDecoration: 'none', color: 'inherit' }}>
                <BrandLogo size="small" subtitle={false} />
              </RouterLink>
              <Stack direction="row" spacing={1} sx={{ display: { xs: 'none', sm: 'flex' } }}>
                <Button
                  component={RouterLink}
                  to="/app"
                  size="small"
                  variant={location.pathname === '/app' ? 'contained' : 'text'}
                  color={location.pathname === '/app' ? 'primary' : 'inherit'}
                  startIcon={<DashboardIcon />}
                >
                  Dashboard
                </Button>
                <Button
                  component={RouterLink}
                  to="/profile"
                  size="small"
                  variant={location.pathname === '/profile' ? 'contained' : 'text'}
                  color={location.pathname === '/profile' ? 'primary' : 'inherit'}
                  startIcon={<PersonIcon />}
                >
                  Profile
                </Button>
              </Stack>
            </Stack>

            <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
              {user && (
                <Chip
                  label={formatRoleLabel(user.role)}
                  color={roleColor(user.role)}
                  variant="outlined"
                  size="small"
                  sx={{ fontWeight: 600 }}
                />
              )}

              {user && (
                <Typography variant="body2" color="text.secondary" sx={{ display: { xs: 'none', md: 'block' } }}>
                  {user.email}
                </Typography>
              )}

              <IconButton
                onClick={handleMenuOpen}
                size="small"
                aria-label="account options"
                aria-controls="user-menu"
                aria-haspopup="true"
                color="inherit"
              >
                <AccountCircleIcon fontSize="medium" />
              </IconButton>

              <Menu
                id="user-menu"
                anchorEl={anchorEl}
                open={Boolean(anchorEl)}
                onClose={handleMenuClose}
                transformOrigin={{ horizontal: 'right', vertical: 'top' }}
                anchorOrigin={{ horizontal: 'right', vertical: 'bottom' }}
                slotProps={{ paper: { sx: { minWidth: 180, mt: 1 } } }}
              >
                <MenuItem
                  component={RouterLink}
                  to="/app"
                  onClick={handleMenuClose}
                  selected={location.pathname === '/app'}
                >
                  <ListItemIcon>
                    <DashboardIcon fontSize="small" />
                  </ListItemIcon>
                  <ListItemText primary="Dashboard" />
                </MenuItem>
                <MenuItem
                  component={RouterLink}
                  to="/profile"
                  onClick={handleMenuClose}
                  selected={location.pathname === '/profile'}
                >
                  <ListItemIcon>
                    <PersonIcon fontSize="small" />
                  </ListItemIcon>
                  <ListItemText primary="Profile" />
                </MenuItem>
                <MenuItem onClick={handleLogout}>
                  <ListItemIcon>
                    <LogoutIcon fontSize="small" />
                  </ListItemIcon>
                  <ListItemText primary="Sign out" />
                </MenuItem>
              </Menu>
            </Stack>
          </Toolbar>
        </Container>
      </AppBar>

      <Box component="main" sx={{ flexGrow: 1, py: { xs: 3, md: 5 } }}>
        <Outlet />
      </Box>
    </Box>
  );
};
