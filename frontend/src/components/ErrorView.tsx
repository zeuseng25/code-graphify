import { Alert, Button, Stack } from '@mantine/core';
import { errorMessage } from '../api/errors';
import { tr } from '../i18n/tr';

/** A failed load: the backend's message or a Turkish notice, with an optional retry. */
export function ErrorView({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <Stack p="md">
      <Alert color="red">{errorMessage(error)}</Alert>
      {onRetry && (
        <Button variant="light" onClick={onRetry}>
          {tr.errors.retry}
        </Button>
      )}
    </Stack>
  );
}
