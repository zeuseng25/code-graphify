import { Alert, Badge, Group, Stack, Text, Title } from '@mantine/core';
import { useParams } from 'react-router';
import { useRun } from '../../api/runs';
import { useIsAdmin } from '../../auth/session';
import { RunStatusBadge } from '../../components/Badges';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { enumLabel } from '../../i18n/enumLabel';
import { formatDateTime, formatNumber, formatRepository } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { NotFoundPage } from '../../pages/NotFoundPage';
import { CancelRunButton } from './CancelRunButton';
import { RunRepositoriesTable } from './RunRepositoriesTable';

/** One run; it refreshes itself while it is RUNNING. */
export function RunPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <NotFoundPage />;
  }
  return <RunView id={id} />;
}

function RunView({ id }: { id: number }) {
  const result = useRun(id);
  const isAdmin = useIsAdmin();
  const view = result.data;
  if (!view) {
    return result.isError ? <ErrorView error={result.error} onRetry={() => void result.refetch()} /> : <Loading />;
  }
  const run = view.run;
  const statuses = Object.entries(view.repositoriesByStatus ?? {});
  const inProgress = view.inProgress ?? [];

  return (
    <Stack gap="lg">
      <Stack gap="xs" role="region" aria-label={tr.runs.title}>
        <Group>
          <Title order={2}>{`#${run?.id ?? id}`}</Title>
          <RunStatusBadge value={run?.status} />
          {run?.cancelRequested && <Badge variant="light" color="orange">{tr.runs.cancelRequested}</Badge>}
          {isAdmin && run?.status === 'RUNNING' && !run.cancelRequested && <CancelRunButton id={id} />}
        </Group>
        <Text size="sm">
          {enumLabel(tr.enums.runTrigger, run?.trigger)} · {enumLabel(tr.enums.runScope, run?.scope)}
          {run?.scopeId != null && ` #${run.scopeId}`} · {run?.startedBy ?? tr.common.none}
        </Text>
        <Text size="sm" c="dimmed">
          {tr.runs.columns.startedAt}: {formatDateTime(run?.startedAt)} · {tr.runs.columns.finishedAt}: {formatDateTime(run?.finishedAt)}
        </Text>
        {view.error && <Alert color="red" title={tr.runs.error}>{view.error}</Alert>}
      </Stack>

      {statuses.length > 0 && (
        <Stack gap="xs">
          <Title order={3}>{tr.runs.byStatus}</Title>
          <Group>
            {statuses.map(([key, count]) => (
              <Badge key={key} variant="light" size="lg">{`${enumLabel(tr.enums.repoStatus, key)}: ${formatNumber(count)}`}</Badge>
            ))}
          </Group>
        </Stack>
      )}

      {inProgress.length > 0 && (
        <Stack gap="xs">
          <Title order={3}>{tr.runs.inProgress}</Title>
          {inProgress.map((repo, index) => (
            <Text key={repo.id ?? index}>{formatRepository(repo)}</Text>
          ))}
        </Stack>
      )}

      <Stack gap="xs">
        <Title order={3}>{tr.runs.repositories}</Title>
        {view.repositories?.length ? <RunRepositoriesTable rows={view.repositories} /> : <Text>{tr.common.empty}</Text>}
      </Stack>
    </Stack>
  );
}
