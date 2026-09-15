import { createTheme } from '@mui/material/styles';

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#0f2942', // Deep navy
      light: '#1e4976',
      dark: '#081827',
      contrastText: '#ffffff',
    },
    secondary: {
      main: '#00796b', // Restrained teal
      light: '#009688',
      dark: '#004d40',
      contrastText: '#ffffff',
    },
    background: {
      default: '#f4f6f8',
      paper: '#ffffff',
    },
    text: {
      primary: '#1e293b',
      secondary: '#526277',
    },
    divider: '#e2e8f0',
    success: { main: '#24705b' },
    warning: { main: '#8a5a12' },
    info: { main: '#225f8a' },
    error: { main: '#b42318' },
  },
  typography: {
    fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
    h4: {
      fontWeight: 700,
      letterSpacing: '-0.02em',
    },
    h5: {
      fontWeight: 600,
      letterSpacing: '-0.01em',
    },
    h6: {
      fontWeight: 600,
    },
    button: {
      textTransform: 'none',
      fontWeight: 600,
    },
  },
  shape: {
    borderRadius: 8,
  },
  components: {
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          minHeight: 40,
          boxShadow: 'none',
          '&:hover': {
            boxShadow: 'none',
          },
        },
      },
    },
    MuiIconButton: {
      styleOverrides: { root: { minWidth: 40, minHeight: 40 } },
    },
    MuiSkeleton: {
      defaultProps: { animation: false },
    },
    MuiTableCell: {
      styleOverrides: {
        head: { color: '#526277', fontSize: '0.75rem', fontWeight: 600 },
        root: { borderColor: '#e2e8f0' },
      },
    },
    MuiAlert: {
      styleOverrides: { message: { minWidth: 0, overflowWrap: 'anywhere' } },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          border: '1px solid #e2e8f0',
          boxShadow: '0 1px 3px 0 rgba(0, 0, 0, 0.05)',
        },
      },
    },
  },
});
