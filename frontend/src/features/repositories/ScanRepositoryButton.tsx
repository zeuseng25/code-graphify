import { Button, Group } from '@mantine/core';
import { useStartRun } from '../../api/runs';
import { tr } from '../../i18n/tr';
import { StartRunError } from '../runs/StartRunError';

/** Admins re-scan one repository (force), then land on the run; a conflict links to the run in progress. */
export function ScanRepositoryButton({ id }: { id: number }) {
  const start = useStartRun();
  return (
    <Group>
      <Button variant="light" loading={start.isPending}
        onClick={() => start.mutate({ scope: 'REPOSITORY', id, force: true })}>
        {tr.admin.runs.scanRepository}
      </Button>
      {start.error && <StartRunError error={start.error} />}
    </Group>
  );
}
