import { QueryProvider } from './app/providers/QueryProvider';
import { AuthProvider } from './app/providers/AuthProvider';
import { AppThemeProvider } from './app/providers/AppThemeProvider';
import { AppRouter } from './app/router/AppRouter';

export function App() {
    return (
        <QueryProvider>
            <AuthProvider>
                <AppThemeProvider>
                    <AppRouter />
                </AppThemeProvider>
            </AuthProvider>
        </QueryProvider>
    );
}

export default App;