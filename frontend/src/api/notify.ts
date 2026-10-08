import { notifications } from '@mantine/notifications';
import { errorMessage } from './errors';

/** A failed action (not a failed page load): the backend's message as a red notification. */
export function notifyError(error: unknown): void {
  notifications.show({ color: 'red', message: errorMessage(error) });
}

/** A finished action: a short green notification (never carries a secret). */
export function notifySuccess(message: string): void {
  notifications.show({ color: 'green', message });
}
