import { Anchor, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { Link } from 'react-router';
import { useArtifactRepositories } from '../../../api/artifactRepositories';
import { EnabledBadge, SyncStatusBadge } from '../../../components/Badges';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { ScrollTable } from '../../../components/ScrollTable';
import { formatDateTime, formatNumber } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';

/** Admin: the configured Maven repositories in resolution order, with their last test. */
export function ArtifactRepositoriesPage() {
  const repositories = useArtifactRepositories();
  const columns = tr.admin.artifacts.columns;

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{tr.admin.artifacts.title}</Title>
        <Button component={Link} to="/admin/artifact-repositories/new">{tr.admin.common.create}</Button>
      </Group>
      {repositories.data ? (
        repositories.data.length ? (
          <ScrollTable highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{columns.name}</Table.Th>
                <Table.Th>{columns.url}</Table.Th>
                <Table.Th>{columns.mirrorOf}</Table.Th>
                <Table.Th>{columns.order}</Table.Th>
                <Table.Th>{columns.state}</Table.Th>
                <Table.Th>{columns.lastTest}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {repositories.data.map((repository, index) => (
                <Table.Tr key={repository.id ?? index}>
                  <Table.Td>
                    {repository.id == null
                      ? repository.name ?? tr.common.none
                      : <Anchor component={Link} to={`/admin/artifact-repositories/${repository.id}`}>{repository.name ?? `#${repository.id}`}</Anchor>}
                  </Table.Td>
                  <Table.Td>{repository.url ?? tr.common.none}</Table.Td>
                  <Table.Td>{repository.mirrorOf ?? tr.common.none}</Table.Td>
                  <Table.Td>{formatNumber(repository.sortOrder)}</Table.Td>
                  <Table.Td><EnabledBadge enabled={repository.enabled} /></Table.Td>
                  <Table.Td>
                    <Group gap="xs">
                      <SyncStatusBadge value={repository.lastTestStatus} />
                      <Text size="sm">{formatDateTime(repository.lastTestAt)}</Text>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
        ) : (
          <Text>{tr.common.empty}</Text>
        )
      ) : repositories.isError ? (
        <ErrorView error={repositories.error} onRetry={() => void repositories.refetch()} />
      ) : (
        <Loading />
      )}
    </Stack>
  );
}
