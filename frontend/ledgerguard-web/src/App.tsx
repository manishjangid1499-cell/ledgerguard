import React from 'react';
import { ThemeProvider, CssBaseline } from '@mui/material';
import { theme } from './app/theme/theme';
import { QueryProvider } from './app/providers/QueryProvider';
import { AuthProvider } from './app/providers/AuthProvider';
import { AppRouter } from './app/router/AppRouter';

export const App: React.FC = () => {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryProvider>
        <AuthProvider>
          <AppRouter />
        </AuthProvider>
      </QueryProvider>
    </ThemeProvider>
  );
};

export default App;
