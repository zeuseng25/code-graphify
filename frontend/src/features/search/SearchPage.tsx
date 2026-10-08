import { Badge, Button, Group, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { ErrorView } from '../../components/ErrorView';
import { ScrollTable } from '../../components/ScrollTable';
import { SymbolLink } from '../../components/SymbolLink';
import { useSymbolSearch, type SymbolKind } from '../../api/symbols';
import { OriginBadge, SymbolKindBadge } from '../../components/Badges';
import { Loading } from '../../components/Loading';
import { Pager } from '../../components/Pager';
import { useUrlState } from '../../hooks/useUrlState';
import { formatNumber } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { RepositorySelect } from './RepositorySelect';

const KINDS = Object.keys(tr.enums.symbolKind) as SymbolKind[];
const isKind = (value: string | null): value is SymbolKind => KINDS.includes(value as SymbolKind);

/** The home page: find a class, method or field; each overload is its own row (web UI spec §4.1 row 3). */
export function SearchPage() {
  const { params, page, update } = useUrlState();
  const q = params.get('q') ?? '';
  const kindParam = params.get('kind');
  const kind = isKind(kindParam) ? kindParam : undefined;
  const repoParam = Number(params.get('repo'));
  const repo = Number.isInteger(repoParam) && repoParam > 0 ? repoParam : undefined;
  const results = useSymbolSearch(q, kind, repo, page);

  return (
    <Stack>
      <Title order={2}>{tr.search.title}</Title>
      <SearchForm key={q} initial={q} onSearch={(text) => update({ q: text })} />
      <Group grow>
        <Select
          label={tr.search.kind}
          placeholder={tr.search.anyKind}
          data={KINDS.map((value) => ({ value, label: tr.enums.symbolKind[value] }))}
          clearable
          value={kind ?? null}
          onChange={(value) => update({ kind: value })}
        />
        <RepositorySelect
          label={tr.search.repository}
          placeholder={tr.search.anyRepository}
          value={repo}
          onChange={(id) => update({ repo: id })}
        />
      </Group>
      {q.trim() === '' ? (
        <Text c="dimmed">{tr.search.hint}</Text>
      ) : !results.data ? (
        results.isError ? <ErrorView error={results.error} onRetry={() => void results.refetch()} /> : <Loading />
      ) : results.data.items?.length ? (
        <>
          <ScrollTable highlightOnHover style={{ opacity: results.isPlaceholderData ? 0.5 : 1 }}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.search.columns.symbol}</Table.Th>
                <Table.Th>{tr.search.columns.kind}</Table.Th>
                <Table.Th>{tr.search.columns.origin}</Table.Th>
                <Table.Th>{tr.search.columns.usages}</Table.Th>
                <Table.Th>{tr.search.columns.repositories}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {results.data.items.map((hit) => (
                <Table.Tr key={hit.id}>
                  <Table.Td>
                    <Text fw={500}><SymbolLink symbol={hit} /></Text>
                    <Text size="xs" c="dimmed">{hit.key}</Text>
                    {hit.nameOnly && <Badge size="xs" color="orange">{tr.search.nameOnly}</Badge>}
                  </Table.Td>
                  <Table.Td><SymbolKindBadge value={hit.kind} /></Table.Td>
                  <Table.Td><OriginBadge value={hit.origin} /></Table.Td>
                  <Table.Td>{formatNumber(hit.usageCount)}</Table.Td>
                  <Table.Td>{formatNumber(hit.repositoryCount)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
          <Pager page={page} size={results.data.size} total={results.data.total} onChange={(next) => update({ page: next })} />
        </>
      ) : (
        <Text>{tr.common.empty}</Text>
      )}
    </Stack>
  );
}

/** The query box; keyed by the URL's query so a back/forward navigation resets it. */
function SearchForm({ initial, onSearch }: { initial: string; onSearch: (text: string) => void }) {
  const [text, setText] = useState(initial);
  return (
    <form onSubmit={(event) => { event.preventDefault(); onSearch(text.trim()); }}>
      <Group align="end">
        <TextInput
          style={{ flex: 1 }}
          label={tr.search.query}
          value={text}
          onChange={(event) => setText(event.currentTarget.value)}
        />
        <Button type="submit">{tr.common.search}</Button>
      </Group>
    </form>
  );
}
