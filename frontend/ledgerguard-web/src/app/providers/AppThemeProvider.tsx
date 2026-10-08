import {
    useContext,
    useEffect,
    useMemo,
    useState,
} from 'react';
import type { ReactNode } from 'react';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { AuthContext } from './authContext';
import { ThemeContext } from './themeContext';
import { createAppTheme } from '../theme/theme';
import { getFeatureAvailability } from '../../shared/api/featureApi';

interface Props {
    children: ReactNode;
}

interface SessionThemeProps extends Props {
    userId: string | null;
}

interface ThemeState {
    available: boolean;
    prefersDark: boolean;
}

function preferenceKey(userId: string): string {
    return `ledgerguard:theme:${userId}`;
}

function readPreference(userId: string): boolean {
    try {
        return localStorage.getItem(preferenceKey(userId)) === 'dark';
    } catch {
        return false;
    }
}

function SessionThemeProvider({
                                  children,
                                  userId,
                              }: SessionThemeProps) {
    const [state, setState] = useState<ThemeState>({
        available: false,
        prefersDark: false,
    });

    useEffect(() => {
        if (!userId) {
            return;
        }

        const accountId = userId;
        const controller = new AbortController();
        let active = true;

        async function loadAvailability() {
            try {
                const result = await getFeatureAvailability(
                    controller.signal,
                );

                if (active) {
                    setState({
                        available: result.darkModeAvailable,
                        prefersDark: readPreference(accountId),
                    });
                }
            } catch {
                if (active) {
                    setState({
                        available: false,
                        prefersDark: false,
                    });
                }
            }
        }

        void loadAvailability();

        return () => {
            active = false;
            controller.abort();
        };
    }, [userId]);

    const darkModeAvailable = Boolean(userId) && state.available;
    const isDarkMode = darkModeAvailable && state.prefersDark;

    const muiTheme = useMemo(
        () => createAppTheme(isDarkMode ? 'dark' : 'light'),
        [isDarkMode],
    );

    function toggleTheme() {
        if (!userId || !darkModeAvailable) {
            return;
        }

        const prefersDark = !state.prefersDark;

        setState({ ...state, prefersDark });

        try {
            localStorage.setItem(
                preferenceKey(userId),
                prefersDark ? 'dark' : 'light',
            );
        } catch {
            // Theme switching works even when storage is unavailable.
        }
    }

    return (
        <ThemeContext.Provider
            value={{ darkModeAvailable, isDarkMode, toggleTheme }}
        >
            <ThemeProvider theme={muiTheme}>
                <CssBaseline />
                {children}
            </ThemeProvider>
        </ThemeContext.Provider>
    );
}

export function AppThemeProvider({ children }: Props) {
    const auth = useContext(AuthContext);

    if (!auth) {
        throw new Error('AppThemeProvider requires AuthProvider.');
    }

    const userId = auth.status === 'authenticated'
        ? auth.user?.id ?? null
        : null;

    return (
        <SessionThemeProvider
            key={userId ?? 'signed-out'}
            userId={userId}
        >
            {children}
        </SessionThemeProvider>
    );
}