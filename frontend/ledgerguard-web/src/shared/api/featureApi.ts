import { apiClient } from './apiClient';

export interface FeatureAvailability {
    darkModeAvailable: boolean;
}

export async function getFeatureAvailability(
    signal?: AbortSignal,
): Promise<FeatureAvailability> {
    const response = await apiClient<FeatureAvailability>(
        '/api/features',
        { method: 'GET', signal },
    );

    return {
        darkModeAvailable: response?.darkModeAvailable === true,
    };
}