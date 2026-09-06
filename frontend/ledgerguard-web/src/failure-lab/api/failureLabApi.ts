import {
  EnvironmentStatusView,
  LabRunView,
  ScenarioId,
  ScenarioMetadataView,
  StartScenarioRequest,
} from '../types/failureLab.types';

const LAB_BASE_URL = (import.meta as unknown as { env?: Record<string, string> }).env?.VITE_FAILURE_LAB_API_BASE_URL || 'http://127.0.0.1:8083';

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let errorDetail = `Request failed with status ${res.status}`;
    try {
      const errJson = await res.json();
      if (errJson.error) {
        errorDetail = errJson.error;
      } else if (errJson.message) {
        errorDetail = errJson.message;
      }
    } catch {
      // ignore
    }
    const error = new Error(errorDetail);
    (error as unknown as { status: number }).status = res.status;
    throw error;
  }
  return res.json() as Promise<T>;
}

export async function fetchEnvironment(): Promise<EnvironmentStatusView> {
  const res = await fetch(`${LAB_BASE_URL}/api/lab/environment`, {
    headers: { Accept: 'application/json' },
  });
  return handleResponse<EnvironmentStatusView>(res);
}

export async function fetchScenarios(): Promise<ScenarioMetadataView[]> {
  const res = await fetch(`${LAB_BASE_URL}/api/lab/scenarios`, {
    headers: { Accept: 'application/json' },
  });
  return handleResponse<ScenarioMetadataView[]>(res);
}

export async function startScenarioRun(scenarioId: ScenarioId): Promise<LabRunView> {
  const body: StartScenarioRequest = { scenarioId };
  const res = await fetch(`${LAB_BASE_URL}/api/lab/runs`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(body),
  });
  return handleResponse<LabRunView>(res);
}

export async function fetchActiveRun(): Promise<LabRunView | null> {
  const res = await fetch(`${LAB_BASE_URL}/api/lab/runs/active`, {
    headers: { Accept: 'application/json' },
  });
  if (res.status === 204) {
    return null;
  }
  return handleResponse<LabRunView>(res);
}

export async function fetchRunById(runId: string): Promise<LabRunView> {
  const res = await fetch(`${LAB_BASE_URL}/api/lab/runs/${runId}`, {
    headers: { Accept: 'application/json' },
  });
  return handleResponse<LabRunView>(res);
}

export async function fetchRunHistory(limit = 20): Promise<LabRunView[]> {
  const res = await fetch(`${LAB_BASE_URL}/api/lab/runs?limit=${limit}`, {
    headers: { Accept: 'application/json' },
  });
  return handleResponse<LabRunView[]>(res);
}
