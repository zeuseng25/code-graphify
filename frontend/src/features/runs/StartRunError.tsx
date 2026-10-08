import { Alert, Anchor } from '@mantine/core';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import { errorMessage } from '../../api/errors';
import { tr } from '../../i18n/tr';

/** A refused run start; a 409 (another run in progress) links to that run. */
export function StartRunError({ error }: { error: unknown }) {
  const conflictRun = error instanceof ApiError && error.status === 409 ? error.problem.runId : undefined;
  return (
    <Alert color="red">
      {errorMessage(error)}
      {conflictRun != null && (
        <>
          {' '}
          <Anchor component={Link} to={`/runs/${conflictRun}`}>{tr.admin.runs.openRunning}</Anchor>
        </>
      )}
    </Alert>
  );
}
