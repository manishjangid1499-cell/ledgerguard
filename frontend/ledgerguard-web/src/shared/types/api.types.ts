export interface FieldValidationError {
  field: string;
  message: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail: string;
  instance?: string;
  errorCode?: string;
  timestamp?: string;
  errors?: FieldValidationError[];
}

export class ApiError extends Error {
  public readonly status: number;
  public readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail || problem.title || 'An API error occurred.');
    this.name = 'ApiError';
    this.status = problem.status;
    this.problem = problem;
  }
}
