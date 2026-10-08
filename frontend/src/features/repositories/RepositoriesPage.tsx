import { Anchor, Badge, Button, Group, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router';
import { useRepositories } from '../../api/repositories';
import { RepoStatusBadge } from '../../components/Badges';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { Pager } from '../../components/Pager';
import { ScrollTable } from '../../components/ScrollTable';
import { useUrlState } from '../../hooks/useUrlState';
import { formatDateTime, formatNumber, formatRepository } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { CommitCode } from '../runs/RunRepositoriesTable';

const STATUSES = Object.keys(tr.enums.repoStatus);

/** Indexed repositories, filtered by name and last index status (web UI spec §4.1 row 7). */
export function RepositoriesPage() {
  const { params, page, update } = useUrlState();
  const q = params.get('q') ?? '';
  const statusParam = params.get('status');
  const status = statusParam && STATUSES.includes(statusParam) ? statusParam : undefined;
  const repositories = useRepositories(q, status, page);
  const columns = tr.repositories.columns;

  return (
    <Stack>
      <Title order={2}>{tr.repositories.title}</Title>
      <Group align="end" grow>
        <QueryForm key={q} initial={q} onSearch={(text) => update({ q: text })} />
        <Select
          label={tr.repositories.status}
          placeholder={tr.repositories.anyStatus}
          data={STATUSES.map((value) => ({ value, label: tr.enums.repoStatus[value as keyof typeof tr.enums.repoStatus] }))}
          clearable
          value={status ?? null}
          onChange={(value) => update({ status: value })}
        />
      </Group>
      {repositories.data ? (
        repositories.data.items?.length ? (
          <>
            <ScrollTable highlightOnHover style={{ opacity: repositories.isPlaceholderData ? 0.5 : 1 }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{columns.repository}</Table.Th>
                  <Table.Th>{columns.status}</Table.Th>
                  <Table.Th>{columns.indexedAt}</Table.Th>
                  <Table.Th>{columns.commit}</Table.Th>
                  <Table.Th>{columns.modules}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {repositories.data.items.map((repo) => (
                  <Table.Tr key={repo.id}>
                    <Table.Td>
                      <Group gap="xs">
                        <Anchor component={Link} to={`/repositories/${repo.id}`}>{formatRepository(repo)}</Anchor>
                        {repo.active === false && <Badge variant="light" color="gray">{tr.repositories.inactive}</Badge>}
                      </Group>
                    </Table.Td>
                    <Table.Td><RepoStatusBadge value={repo.lastStatus} /></Table.Td>
                    <Table.Td>{formatDateTime(repo.lastIndexedAt)}</Table.Td>
                    <Table.Td><CommitCode commit={repo.lastIndexedCommit} /></Table.Td>
                    <Table.Td>{formatNumber(repo.moduleCount)}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </ScrollTable>
            <Pager page={page} size={repositories.data.size} total={repositories.data.total} onChange={(next) => update({ page: next })} />
          </>
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

/** The name filter; keyed by the URL's query so back/forward resets it. */
function QueryForm({ initial, onSearch }: { initial: string; onSearch: (text: string) => void }) {
  const [text, setText] = useState(initial);
  return (
    <form onSubmit={(event) => { event.preventDefault(); onSearch(text.trim()); }}>
      <Group align="end">
        <TextInput style={{ flex: 1 }} label={tr.repositories.query} value={text} onChange={(event) => setText(event.currentTarget.value)} />
        <Button type="submit">{tr.common.search}</Button>
      </Group>
    </form>
  );
}
