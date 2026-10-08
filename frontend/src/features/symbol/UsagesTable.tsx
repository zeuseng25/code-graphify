import { Group, MultiSelect, Select, Stack, Table, Text, Title } from '@mantine/core';
import { useUsages, useUsageSummary, type Confidence, type UsageKind } from '../../api/symbols';
import { ConfidenceBadge, UsageKindBadge } from '../../components/Badges';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { Pager } from '../../components/Pager';
import { ScrollTable } from '../../components/ScrollTable';
import { SnippetCode } from '../../components/SnippetCode';
import { SymbolLink } from '../../components/SymbolLink';
import { NO_WRAP, WRAP_ANYWHERE } from '../../components/wrapStyles';
import { useUrlState } from '../../hooks/useUrlState';
import { formatLocation, formatNumber, formatRepository, formatRepositoryModule } from '../../i18n/format';
import { tr } from '../../i18n/tr';

const CONFIDENCES = Object.keys(tr.enums.confidence) as Confidence[];
const KINDS = Object.keys(tr.enums.usageKind) as UsageKind[];

/** Every usage of the symbol, filtered by confidence, kind and repository (all in the URL). */
export function UsagesTable({ id }: { id: number }) {
  const { params, page, update } = useUrlState();
  const confidence = params.getAll('confidence').filter((value): value is Confidence => CONFIDENCES.includes(value as Confidence));
  const kind = params.getAll('usageKind').filter((value): value is UsageKind => KINDS.includes(value as UsageKind));
  const repoParam = Number(params.get('repo'));
  const repo = Number.isInteger(repoParam) && repoParam > 0 ? repoParam : undefined;
  const usages = useUsages(id, { confidence, kind, repo }, page);
  const summary = useUsageSummary(id);
  const repositories = (summary.data?.byRepository ?? []).flatMap((entry) =>
    entry.repository?.id == null ? [] : [{ value: String(entry.repository.id), label: formatRepository(entry.repository) }]);

  return (
    <Stack>
      <Title order={3}>{tr.symbol.usages}{usages.data && ` ${tr.symbol.usagesTotal(formatNumber(usages.data.total))}`}</Title>
      <Group grow align="end">
        <MultiSelect
          label={tr.symbol.filters.confidence}
          data={CONFIDENCES.map((value) => ({ value, label: tr.enums.confidence[value] }))}
          value={confidence}
          onChange={(values) => update({ confidence: values })}
          clearable
        />
        <MultiSelect
          label={tr.symbol.filters.kind}
          data={KINDS.map((value) => ({ value, label: tr.enums.usageKind[value] }))}
          value={kind}
          onChange={(values) => update({ usageKind: values })}
          clearable
        />
        <Select
          label={tr.symbol.filters.repository}
          placeholder={tr.search.anyRepository}
          data={repositories}
          value={repo == null ? null : String(repo)}
          onChange={(value) => update({ repo: value })}
          searchable
          clearable
        />
      </Group>
      {!usages.data ? (
        usages.isError ? <ErrorView error={usages.error} onRetry={() => void usages.refetch()} /> : <Loading />
      ) : usages.data.items?.length ? (
        <>
          <ScrollTable highlightOnHover style={{ opacity: usages.isPlaceholderData ? 0.5 : 1 }}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.symbol.columns.from}</Table.Th>
                <Table.Th>{tr.symbol.columns.kind}</Table.Th>
                <Table.Th>{tr.symbol.columns.confidence}</Table.Th>
                <Table.Th>{tr.symbol.columns.location}</Table.Th>
                <Table.Th>{tr.symbol.columns.snippet}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {usages.data.items.map((usage) => (
                <Table.Tr key={usage.id}>
                  <Table.Td style={WRAP_ANYWHERE}><SymbolLink symbol={usage.from} /></Table.Td>
                  <Table.Td style={NO_WRAP}><UsageKindBadge value={usage.kind} /></Table.Td>
                  <Table.Td style={NO_WRAP}><ConfidenceBadge value={usage.confidence} /></Table.Td>
                  <Table.Td style={WRAP_ANYWHERE}>
                    <Text size="sm">{formatRepositoryModule(usage.repository, usage.modulePath)}</Text>
                    <Text size="xs" c="dimmed">{formatLocation(usage.filePath, usage.line)}</Text>
                  </Table.Td>
                  <Table.Td style={WRAP_ANYWHERE}>{usage.snippet && <SnippetCode>{usage.snippet}</SnippetCode>}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
          <Pager page={page} size={usages.data.size} total={usages.data.total} onChange={(next) => update({ page: next })} />
        </>
      ) : (
        <Text>{tr.common.empty}</Text>
      )}
    </Stack>
  );
}
