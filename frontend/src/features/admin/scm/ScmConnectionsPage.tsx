import { Anchor, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { Link } from 'react-router';
import { useScmConnections } from '../../../api/scmConnections';
import { EnabledBadge, SyncStatusBadge } from '../../../components/Badges';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { ScrollTable } from '../../../components/ScrollTable';
import { enumLabel } from '../../../i18n/enumLabel';
import { formatDateTime, formatNumber } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';

/** Admin: the configured repo connections with their last test and sync. */
export function ScmConnectionsPage() {
  const connections = useScmConnections();
  const columns = tr.admin.scm.columns;

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{tr.admin.scm.title}</Title>
        <Button component={Link} to="/admin/scm-connections/new">{tr.admin.common.create}</Button>
      </Group>
      {connections.data ? (
        connections.data.length ? (
          <ScrollTable highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{columns.name}</Table.Th>
                <Table.Th>{columns.type}</Table.Th>
                <Table.Th>{columns.baseUrl}</Table.Th>
                <Table.Th>{columns.state}</Table.Th>
                <Table.Th>{columns.repositories}</Table.Th>
                <Table.Th>{columns.lastTest}</Table.Th>
                <Table.Th>{columns.lastSync}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {connections.data.map((connection, index) => (
                <Table.Tr key={connection.id ?? index}>
                  <Table.Td>
                    {connection.id == null
                      ? connection.name ?? tr.common.none
                      : <Anchor component={Link} to={`/admin/scm-connections/${connection.id}`}>{connection.name ?? `#${connection.id}`}</Anchor>}
                  </Table.Td>
                  <Table.Td>{enumLabel(tr.enums.scmType, connection.type)}</Table.Td>
                  <Table.Td>{connection.baseUrl ?? tr.common.none}</Table.Td>
                  <Table.Td><EnabledBadge enabled={connection.enabled} /></Table.Td>
                  <Table.Td>{formatNumber(connection.repositoryCount)}</Table.Td>
                  <Table.Td>
                    <Group gap="xs">
                      <SyncStatusBadge value={connection.lastTestStatus} />
                      <Text size="sm">{formatDateTime(connection.lastTestAt)}</Text>
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Stack gap={2}>
                      <Group gap="xs">
                        <SyncStatusBadge value={connection.lastSyncStatus} />
                        <Text size="sm">{formatDateTime(connection.lastSyncAt)}</Text>
                      </Group>
                      {connection.lastSyncError && <Text size="xs" c="red">{connection.lastSyncError}</Text>}
                    </Stack>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
        ) : (
          <Text>{tr.common.empty}</Text>
        )
      ) : connections.isError ? (
        <ErrorView error={connections.error} onRetry={() => void connections.refetch()} />
      ) : (
        <Loading />
      )}
    </Stack>
  );
}
