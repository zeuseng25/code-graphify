import { Anchor, Badge, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { Link, useParams } from 'react-router';
import { useRepository, useRepositoryRuns } from '../../api/repositories';
import { useIsAdmin } from '../../auth/session';
import { RepoStatusBadge } from '../../components/Badges';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { Pager } from '../../components/Pager';
import { ScrollTable } from '../../components/ScrollTable';
import { NO_WRAP, WRAP_ANYWHERE } from '../../components/wrapStyles';
import { useUrlState } from '../../hooks/useUrlState';
import { enumLabel } from '../../i18n/enumLabel';
import { formatDateTime, formatModulePath, formatRepository } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { NotFoundPage } from '../../pages/NotFoundPage';
import { ScanRepositoryButton } from './ScanRepositoryButton';
import { CommitCode, formatCounts, formatDuration } from '../runs/RunRepositoriesTable';

/** One repository: status, modules and its index run history. */
export function RepositoryPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <NotFoundPage />;
  }
  return <RepositoryView id={id} />;
}

function RepositoryView({ id }: { id: number }) {
  const detail = useRepository(id);
  const isAdmin = useIsAdmin();
  const data = detail.data;
  if (!data) {
    return detail.isError ? <ErrorView error={detail.error} onRetry={() => void detail.refetch()} /> : <Loading />;
  }
  const repo = data.repository;
  const modules = data.modules ?? [];

  return (
    <Stack gap="lg">
      <Stack gap="xs">
        <Group>
          <Title order={2}>{formatRepository(repo)}</Title>
          <RepoStatusBadge value={repo?.lastStatus} />
          {repo?.active === false && <Badge variant="light" color="gray">{tr.repositories.inactive}</Badge>}
          <Button component={Link} to={`/repositories/${id}/graph`} variant="light">{tr.graph.show}</Button>
          {isAdmin && <ScanRepositoryButton id={id} />}
        </Group>
        <Group gap="md">
          <Text size="sm">{tr.repositories.branch}: {repo?.defaultBranch ?? tr.common.none}</Text>
          <Text size="sm">{tr.repositories.columns.commit}: <CommitCode commit={repo?.lastIndexedCommit} /></Text>
          <Text size="sm">{tr.repositories.columns.indexedAt}: {formatDateTime(repo?.lastIndexedAt)}</Text>
        </Group>
      </Stack>

      <Stack gap="xs">
        <Title order={3}>{tr.repositories.modules}</Title>
        {modules.length ? (
          <ScrollTable>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.repositories.moduleColumns.path}</Table.Th>
                <Table.Th>{tr.repositories.moduleColumns.coordinates}</Table.Th>
                <Table.Th>{tr.repositories.moduleColumns.classpath}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {modules.map((module, index) => (
                <Table.Tr key={module.id ?? index}>
                  <Table.Td style={WRAP_ANYWHERE}>{formatModulePath(module.path)}</Table.Td>
                  <Table.Td style={WRAP_ANYWHERE}>
                    {`${module.groupId ?? tr.common.none}:${module.artifactId ?? tr.common.none}:${module.version ?? tr.common.none}`}
                  </Table.Td>
                  <Table.Td>{enumLabel(tr.enums.classpathMode, module.classpathMode)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
        ) : (
          <Text>{tr.common.empty}</Text>
        )}
      </Stack>

      <RunHistory id={id} />
    </Stack>
  );
}

function RunHistory({ id }: { id: number }) {
  const { page, update } = useUrlState();
  const runs = useRepositoryRuns(id, page);
  const columns = tr.repositories.runColumns;
  return (
    <Stack gap="xs">
      <Title order={3}>{tr.repositories.runs}</Title>
      {runs.data ? (
        runs.data.items?.length ? (
          <>
            <ScrollTable highlightOnHover style={{ opacity: runs.isPlaceholderData ? 0.5 : 1 }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{columns.run}</Table.Th>
                  <Table.Th>{columns.status}</Table.Th>
                  <Table.Th>{columns.commit}</Table.Th>
                  <Table.Th>{columns.counts}</Table.Th>
                  <Table.Th>{columns.duration}</Table.Th>
                  <Table.Th>{columns.finishedAt}</Table.Th>
                  <Table.Th>{columns.error}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {runs.data.items.map((row, index) => (
                  <Table.Tr key={row.runId ?? index}>
                    <Table.Td>
                      {row.runId == null ? tr.common.none : <Anchor component={Link} to={`/runs/${row.runId}`}>{`#${row.runId}`}</Anchor>}
                    </Table.Td>
                    <Table.Td style={NO_WRAP}><RepoStatusBadge value={row.status} /></Table.Td>
                    <Table.Td style={NO_WRAP}><CommitCode commit={row.commit} /></Table.Td>
                    <Table.Td style={NO_WRAP}>{formatCounts(row)}</Table.Td>
                    <Table.Td style={NO_WRAP}>{formatDuration(row.durationMs)}</Table.Td>
                    <Table.Td style={NO_WRAP}>{formatDateTime(row.finishedAt)}</Table.Td>
                    <Table.Td style={WRAP_ANYWHERE}>{row.error && <Text size="sm" c="red">{row.error}</Text>}</Table.Td>
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
