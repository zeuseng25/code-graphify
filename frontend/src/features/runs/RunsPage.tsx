import { Anchor, Badge, Group, Stack, Table, Text, Title } from '@mantine/core';
import { Link } from 'react-router';
import { useRuns } from '../../api/runs';
import { RunStatusBadge } from '../../components/Badges';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { Pager } from '../../components/Pager';
import { ScrollTable } from '../../components/ScrollTable';
import { useIsAdmin } from '../../auth/session';
import { useUrlState } from '../../hooks/useUrlState';
import { enumLabel } from '../../i18n/enumLabel';
import { formatDateTime } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { StartRunForm } from './StartRunForm';

/** Index runs, newest first (web UI spec §4.1 row 8). */
export function RunsPage() {
  const { page, update } = useUrlState();
  const runs = useRuns(page);
  const columns = tr.runs.columns;
  const isAdmin = useIsAdmin();

  return (
    <Stack>
      <Title order={2}>{tr.runs.title}</Title>
      {isAdmin && <StartRunForm />}
      {runs.data ? (
        runs.data.items?.length ? (
          <>
            <ScrollTable highlightOnHover style={{ opacity: runs.isPlaceholderData ? 0.5 : 1 }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{columns.run}</Table.Th>
                  <Table.Th>{columns.trigger}</Table.Th>
                  <Table.Th>{columns.scope}</Table.Th>
                  <Table.Th>{columns.status}</Table.Th>
                  <Table.Th>{columns.startedBy}</Table.Th>
                  <Table.Th>{columns.startedAt}</Table.Th>
                  <Table.Th>{columns.finishedAt}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {runs.data.items.map((run) => (
                  <Table.Tr key={run.id}>
                    <Table.Td>{run.id == null ? tr.common.none : <Anchor component={Link} to={`/runs/${run.id}`}>{`#${run.id}`}</Anchor>}</Table.Td>
                    <Table.Td>{enumLabel(tr.enums.runTrigger, run.trigger)}</Table.Td>
                    <Table.Td>
                      {enumLabel(tr.enums.runScope, run.scope)}
                      {run.scopeId != null && ` #${run.scopeId}`}
                    </Table.Td>
                    <Table.Td>
                      <Group gap="xs">
                        <RunStatusBadge value={run.status} />
                        {run.cancelRequested && <Badge variant="light" color="orange">{tr.runs.cancelRequested}</Badge>}
                      </Group>
                    </Table.Td>
                    <Table.Td>{run.startedBy ?? tr.common.none}</Table.Td>
                    <Table.Td>{formatDateTime(run.startedAt)}</Table.Td>
                    <Table.Td>{formatDateTime(run.finishedAt)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </ScrollTable>
            <Pager page={page} size={runs.data.size} total={runs.data.total} onChange={(next) => update({ page: next })} />
          </>
        ) : (
          <Text>{tr.common.empty}</Text>
        )
      ) : runs.isError ? (
        <ErrorView error={runs.error} onRetry={() => void runs.refetch()} />
      ) : (
        <Loading />
      )}
    </Stack>
  );
}
