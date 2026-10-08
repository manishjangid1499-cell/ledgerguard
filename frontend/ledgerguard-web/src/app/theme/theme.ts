import { createTheme } from '@mui/material/styles';
import type { PaletteMode } from '@mui/material';

export function createAppTheme(mode: PaletteMode = 'light') {
  const dark = mode === 'dark';

  return createTheme({
    palette: {
      mode,
      primary: {
        main: dark ? '#90caf9' : '#0f2942',
        light: dark ? '#c3e3ff' : '#1e4976',
        dark: dark ? '#5d99c6' : '#081827',
        contrastText: dark ? '#102030' : '#ffffff',
      },
      secondary: {
        main: dark ? '#80cbc4' : '#00796b',
        light: dark ? '#b2dfdb' : '#009688',
        dark: dark ? '#4f9a94' : '#004d40',
        contrastText: dark ? '#102030' : '#ffffff',
      },
      background: {
        default: dark ? '#101820' : '#f4f6f8',
        paper: dark ? '#182430' : '#ffffff',
      },
      text: {
        primary: dark ? '#e8eef5' : '#1e293b',
        secondary: dark ? '#b0bfce' : '#526277',
      },
      divider: dark ? '#344454' : '#e2e8f0',
      success: {
        main: dark ? '#81c784' : '#24705b',
      },
      warning: {
        main: dark ? '#ffcc80' : '#8a5a12',
      },
      info: {
        main: dark ? '#90caf9' : '#225f8a',
      },
      error: {
        main: dark ? '#ef9a9a' : '#b42318',
      },
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
        styleOverrides: {
          root: {
            minWidth: 40,
            minHeight: 40,
          },
        },
      },
      MuiSkeleton: {
        defaultProps: {
          animation: false,
        },
      },
      MuiTableCell: {
        styleOverrides: {
          head: ({ theme }) => ({
            color: theme.palette.text.secondary,
            fontSize: '0.75rem',
            fontWeight: 600,
          }),
          root: ({ theme }) => ({
            borderColor: theme.palette.divider,
          }),
        },
      },
      MuiAlert: {
        styleOverrides: {
          message: {
            minWidth: 0,
            overflowWrap: 'anywhere',
          },
        },
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
          root: ({ theme }) => ({
            border: `1px solid ${theme.palette.divider}`,
            boxShadow: '0 1px 3px 0 rgba(0, 0, 0, 0.05)',
          }),
        },
      },
    },
  });
}

// Preserves existing imports until the dynamic theme provider is connected.
export const theme = createAppTheme('light');