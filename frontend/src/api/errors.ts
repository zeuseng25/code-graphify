import { tr } from '../i18n/tr';
import { ApiError } from './client';

/** The Turkish text for a failed call: the backend's own message when it sent one, else a fixed text. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.problem.detail) {
      return error.problem.detail;
    }
    return error.status === 403 ? tr.errors.forbidden : tr.errors.server;
  }
  return tr.errors.unreachable;
}
