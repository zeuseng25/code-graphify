import { Alert, Button, Stack } from '@mantine/core';
import { useRouteError } from 'react-router';
import { tr } from '../i18n/tr';
import { isStaleChunkError } from './staleChunk';

/** A page that failed to render: a Turkish notice and a reload, instead of the router's English default. */
export function RouteErrorPage() {
  const error = useRouteError();
  return (
    <Stack p="md">
      <Alert color={isStaleChunkError(error) ? 'blue' : 'red'}>
        {isStaleChunkError(error) ? tr.errors.newVersion : tr.errors.unexpected}
      </Alert>
      <Button variant="light" onClick={() => window.location.reload()} w="fit-content">
        {tr.errors.reload}
      </Button>
    </Stack>
  );
}
