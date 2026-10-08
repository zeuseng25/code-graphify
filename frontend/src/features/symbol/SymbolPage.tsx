import { Badge, Button, Group, Stack, Table, Text, Title } from '@mantine/core';
import { Link, useParams } from 'react-router';
import type { components } from '../../api/schema';
import { useSymbol } from '../../api/symbols';
import { OriginBadge, SymbolKindBadge } from '../../components/Badges';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { SymbolLink } from '../../components/SymbolLink';
import { WRAP_ANYWHERE } from '../../components/wrapStyles';
import { formatLocation, formatModulePath, formatRepository } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { NotFoundPage } from '../../pages/NotFoundPage';
import { UsageSummaryView } from './UsageSummaryView';
import { UsagesTable } from './UsagesTable';

type SymbolRef = components['schemas']['SymbolRef'];

/** One symbol: where it is declared, its hierarchy, how much it is used and each usage. */
export function SymbolPage() {
  const id = Number(useParams().id);
  if (!Number.isInteger(id) || id <= 0) {
    return <NotFoundPage />;
  }
  return <SymbolDetailView id={id} />;
}

function SymbolDetailView({ id }: { id: number }) {
  const detail = useSymbol(id);
  const data = detail.data;
  if (!data) {
    return detail.isError ? <ErrorView error={detail.error} onRetry={() => void detail.refetch()} /> : <Loading />;
  }
  const groups: [string, SymbolRef[]][] = [
    [tr.symbol.parent, data.parent ? [data.parent] : []],
    [tr.symbol.supertypes, data.supertypes ?? []],
    [tr.symbol.subtypes, data.subtypes ?? []],
    [tr.symbol.overrides, data.overrides ?? []],
    [tr.symbol.overriddenBy, data.overriddenBy ?? []],
    [tr.symbol.members, data.members ?? []],
  ];
  const shown = groups.filter(([, items]) => items.length > 0);

  return (
    <Stack gap="lg">
      <Group justify="space-between" align="flex-start">
        <Stack gap={4}>
          <Title order={2}>{data.symbol?.display ?? data.symbol?.key}</Title>
          <Text size="sm" c="dimmed">{data.symbol?.key}</Text>
          <Group gap="xs">
            <SymbolKindBadge value={data.symbol?.kind} />
            <OriginBadge value={data.origin} />
            {data.nameOnly && <Badge color="orange">{tr.search.nameOnly}</Badge>}
          </Group>
        </Stack>
        <Button component={Link} to={`/impact?symbol=${id}`}>{tr.symbol.analyzeImpact}</Button>
      </Group>

      {(data.declarations?.length ?? 0) > 0 && (
        <Stack>
          <Title order={3}>{tr.symbol.declarations}</Title>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.symbol.columns.repository}</Table.Th>
                <Table.Th>{tr.symbol.columns.module}</Table.Th>
                <Table.Th>{tr.symbol.columns.location}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.declarations?.map((declaration, index) => (
                <Table.Tr key={index}>
                  <Table.Td style={WRAP_ANYWHERE}>{formatRepository(declaration.repository)}</Table.Td>
                  <Table.Td style={WRAP_ANYWHERE}>{formatModulePath(declaration.modulePath)}</Table.Td>
                  <Table.Td style={WRAP_ANYWHERE}>{formatLocation(declaration.filePath, declaration.line)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
      )}

      {shown.length > 0 && (
        <Stack>
          <Title order={3}>{tr.symbol.hierarchy}</Title>
          {shown.map(([label, items]) => (
            <Stack key={label} gap={2}>
              <Text fw={500}>{label}</Text>
              {items.map((item) => (
                <SymbolLink key={item.id ?? item.key} symbol={item} />
              ))}
            </Stack>
          ))}
        </Stack>
      )}

      <UsageSummaryView id={id} />
      <UsagesTable id={id} />
    </Stack>
  );
}
