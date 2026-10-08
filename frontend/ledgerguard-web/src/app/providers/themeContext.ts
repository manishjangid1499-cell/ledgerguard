import { createContext } from 'react';

export interface ThemeContextType {
    darkModeAvailable: boolean;
    isDarkMode: boolean;
    toggleTheme: () => void;
}

export const ThemeContext = createContext<ThemeContextType | undefined>(
    undefined,
);