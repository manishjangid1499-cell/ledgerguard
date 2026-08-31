import { ProblemDetail } from '../types/api.types';

export function parseProblemDetail(status: number, data: unknown): ProblemDetail {
  if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>;
    return {
      type: typeof obj.type === 'string' ? obj.type : undefined,
      title: typeof obj.title === 'string' ? obj.title : undefined,
      status: typeof obj.status === 'number' ? obj.status : status,
      detail: typeof obj.detail === 'string' ? obj.detail : defaultDetailForStatus(status),
      instance: typeof obj.instance === 'string' ? obj.instance : undefined,
      errorCode: typeof obj.errorCode === 'string' ? obj.errorCode : undefined,
      timestamp: typeof obj.timestamp === 'string' ? obj.timestamp : undefined,
      errors: Array.isArray(obj.errors)
        ? obj.errors
            .filter((err): err is { field: string; message: string } =>
              Boolean(err && typeof err === 'object' && typeof err.field === 'string' && typeof err.message === 'string')
            )
            .map((err) => ({ field: err.field, message: err.message }))
        : undefined,
    };
  }

  return {
    status,
    detail: defaultDetailForStatus(status),
  };
}

function defaultDetailForStatus(status: number): string {
  switch (status) {
    case 400:
      return 'The request was invalid or could not be processed.';
    case 401:
      return 'Authentication required. Please sign in.';
    case 403:
      return 'You do not have permission to access this resource.';
    case 404:
      return 'The requested resource was not found.';
    case 405:
      return 'Method not allowed.';
    case 500:
    default:
      return 'An unexpected server error occurred. Please try again later.';
  }
}
