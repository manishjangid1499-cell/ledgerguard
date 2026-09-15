import { ApiError } from '../../shared/types/api.types';
import { getErrorMessage } from '../../shared/api/errorMessage';

export function transferFeedback(error: unknown) {
  const unconfirmed = !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
  const processing = error instanceof ApiError && error.problem.errorCode === 'IDEMPOTENCY_OPERATION_IN_PROGRESS';
  if (unconfirmed) {
    return {
      severity: 'warning' as const, title: 'Transfer result unconfirmed',
      message: 'We could not confirm the result. Keep these details unchanged when retrying so the same request is checked. You can also refresh your transfer history.',
      canRetry: true,
    };
  }
  return {
    severity: processing ? 'info' as const : 'error' as const,
    title: processing ? 'Transfer processing' : 'Transfer could not be completed',
    message: error.status === 404 ? 'The recipient wallet could not be found. Check its wallet ID.' :
      getErrorMessage(error, 'Unable to process this request. Check the wallet ID and amount.'),
    canRetry: false,
  };
}
