import { Accordion, Alert, Badge, Button, Group, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { Suspense, useState } from 'react';
import { lazyWithReload } from '../../app/staleChunk';
import { useExportImpactCsv } from '../../api/impact';
import type { components } from '../../api/schema';
import { ConfidenceBadge, UsageKindBadge } from '../../components/Badges';
import { Loading } from '../../components/Loading';
import { Pager } from '../../components/Pager';
import { ScrollTable } from '../../components/ScrollTable';
import { SnippetCode } from '../../components/SnippetCode';
import { SymbolLink } from '../../components/SymbolLink';
import { NO_WRAP, WRAP_ANYWHERE } from '../../components/wrapStyles';
import { useUiConfig } from '../../config/UiConfigContext';
import { enumLabel } from '../../i18n/enumLabel';
import { formatDateTime, formatLocation, formatNumber, formatRepository, formatRepositoryModule } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import type { ImpactRequest } from './impactParams';

type ImpactResult = components['schemas']['ImpactResult'];
type ImpactNode = components['schemas']['ImpactNode'];
type ImpactEdge = components['schemas']['ImpactEdge'];

/** The graph pulls in cytoscape, which the other screens never need. */
const ImpactGraphView = lazyWithReload(() => import('./ImpactGraphView'));

const SUMMARY_KEYS = ['repositories', 'modules', 'classes', 'methods', 'usages'] as const;

export function ImpactResultView({ result, request }: { result: ImpactResult; request: ImpactRequest }) {
  const { graphMaxNodes } = useUiConfig();
  const exporter = useExportImpactCsv();
  const nodes = result.nodes ?? [];
  const edges = result.edges ?? [];
  const partial = result.summary?.partialClasspathRepositories ?? [];
  const warnings = result.versionWarnings ?? [];
  const staleness = result.staleness ?? [];
  const entryPoints = result.entryPoints ?? [];
  const levels = [...new Set(nodes.map((node) => node.level ?? 0))].sort((a, b) => a - b);

  return (
    <Stack gap="lg">
      <Group>
        <Button variant="light" loading={exporter.isPending} onClick={() => exporter.mutate(request)}>{tr.impact.exportCsv}</Button>
      </Group>
      {result.truncated && <Alert color="orange">{tr.impact.truncated}</Alert>}
      <SimpleGrid cols={{ base: 2, sm: 5 }}>
        {SUMMARY_KEYS.map((key) => (
          <Stack key={key} gap={0}>
            <Text size="xl" fw={600}>{formatNumber(result.summary?.[key])}</Text>
            <Text size="sm" c="dimmed">{tr.impact.summary[key]}</Text>
          </Stack>
        ))}
      </SimpleGrid>
      {partial.length > 0 && (
        <Alert color="orange" title={tr.impact.partialClasspath}>
          {partial.map((repository) => formatRepository(repository)).join(', ')}
        </Alert>
      )}
      {warnings.length > 0 && (
        <Stack gap="xs">
          <Title order={4}>{tr.impact.versionWarnings}</Title>
          <ScrollTable>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.impact.versionColumns.repository}</Table.Th>
                <Table.Th>{tr.impact.versionColumns.dependency}</Table.Th>
                <Table.Th>{tr.impact.versionColumns.used}</Table.Th>
                <Table.Th>{tr.impact.versionColumns.declared}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {warnings.map((warning, index) => (
                <Table.Tr key={index}>
                  <Table.Td>{formatRepositoryModule(warning.repository, warning.modulePath)}</Table.Td>
                  <Table.Td>{warning.dependency ?? tr.common.none}</Table.Td>
                  <Table.Td>{warning.usedVersion ?? tr.common.none}</Table.Td>
                  <Table.Td>{warning.declaredVersion ?? tr.common.none}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
        </Stack>
      )}
      {staleness.length > 0 && (
        <Stack gap="xs">
          <Title order={4}>{tr.impact.staleness}</Title>
          <ScrollTable>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.impact.stalenessColumns.repository}</Table.Th>
                <Table.Th>{tr.impact.stalenessColumns.classpath}</Table.Th>
                <Table.Th>{tr.impact.stalenessColumns.commit}</Table.Th>
                <Table.Th>{tr.impact.stalenessColumns.indexedAt}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {staleness.map((state, index) => (
                <Table.Tr key={index}>
                  <Table.Td>
                    {formatRepositoryModule({ projectKey: state.projectKey, slug: state.repository }, state.modulePath)}
                  </Table.Td>
                  <Table.Td>{enumLabel(tr.enums.classpathMode, state.classpathMode)}</Table.Td>
                  <Table.Td>{state.lastIndexedCommit ?? tr.common.none}</Table.Td>
                  <Table.Td>{formatDateTime(state.lastIndexedAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
        </Stack>
      )}
      {entryPoints.length > 0 && (
        <Stack gap="xs">
          <Title order={4}>{tr.impact.entryPoints}</Title>
          <ScrollTable>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.impact.entryColumns.entry}</Table.Th>
                <Table.Th>{tr.impact.entryColumns.symbol}</Table.Th>
                <Table.Th>{tr.impact.entryColumns.repository}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {entryPoints.map((entry, index) => (
                <Table.Tr key={index}>
                  <Table.Td>
                    {entry.http ? (
                      <Group gap="xs" wrap="nowrap">
                        {entry.httpMethod && <Badge variant="light">{entry.httpMethod}</Badge>}
                        <Text span>{entry.httpPath ?? tr.common.none}</Text>
                      </Group>
                    ) : (
                      <Text span>{entry.label ?? entry.annotation ?? tr.common.none}</Text>
                    )}
                  </Table.Td>
                  <Table.Td><SymbolLink symbol={{ id: entry.symbolId, key: entry.key, display: entry.display }} /></Table.Td>
                  <Table.Td>{formatRepositoryModule(entry.repository, entry.modulePath)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
        </Stack>
      )}
      <Stack gap="xs">
        <Title order={4}>{tr.impact.graph}</Title>
        {nodes.length <= graphMaxNodes
          ? <Suspense fallback={<Loading />}><ImpactGraphView result={result} /></Suspense>
          : <Text>{tr.impact.graphTooLarge(nodes.length, graphMaxNodes)}</Text>}
      </Stack>
      <Stack gap="xs">
        <Title order={4}>{tr.impact.levels}</Title>
        <Accordion multiple defaultValue={levels.map(String)}>
          {levels.map((level) => (
            <LevelItem key={level} level={level}
              nodes={nodes.filter((node) => (node.level ?? 0) === level)}
              edges={edges.filter((edge) => edge.level === level)} names={nodes} />
          ))}
        </Accordion>
      </Stack>
    </Stack>
  );
}

function LevelItem({ level, nodes, edges, names }: { level: number; nodes: ImpactNode[]; edges: ImpactEdge[]; names: ImpactNode[] }) {
  const { pageDefaultSize } = useUiConfig();
  const [page, setPage] = useState(0);
  const byId = new Map(names.map((node) => [node.symbolId, node]));
  const shown = edges.slice(page * pageDefaultSize, (page + 1) * pageDefaultSize);

  return (
    <Accordion.Item value={String(level)}>
      <Accordion.Control>{tr.impact.level(level)}</Accordion.Control>
      <Accordion.Panel>
        <Stack gap="sm">
          {nodes.map((node) => (
            <Group key={node.symbolId} gap="xs">
              <SymbolLink symbol={{ id: node.symbolId, key: node.key, display: node.display }} />
              <Badge variant="light">{enumLabel(tr.enums.nodeRole, node.role)}</Badge>
              <ConfidenceBadge value={node.confidence} />
            </Group>
          ))}
          {level >= 1 && edges.length > 0 && (
            <Stack gap="xs">
              <Text fw={500} size="sm">{tr.impact.locations}</Text>
              <ScrollTable>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{tr.impact.locationColumns.from}</Table.Th>
                    <Table.Th>{tr.impact.locationColumns.kind}</Table.Th>
                    <Table.Th>{tr.impact.locationColumns.confidence}</Table.Th>
                    <Table.Th>{tr.impact.locationColumns.repository}</Table.Th>
                    <Table.Th>{tr.impact.locationColumns.location}</Table.Th>
                    <Table.Th>{tr.impact.locationColumns.snippet}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {shown.map((edge, index) => {
                    const from = byId.get(edge.fromSymbolId);
                    return (
                      <Table.Tr key={`${page}-${index}`}>
                        <Table.Td style={WRAP_ANYWHERE}>
                          <SymbolLink symbol={{ id: edge.fromSymbolId, key: from?.key, display: from?.display }} />
                        </Table.Td>
                        <Table.Td style={NO_WRAP}><UsageKindBadge value={edge.kind} /></Table.Td>
                        <Table.Td style={NO_WRAP}><ConfidenceBadge value={edge.confidence} /></Table.Td>
                        <Table.Td style={WRAP_ANYWHERE}>{formatRepositoryModule(edge.repository, edge.modulePath)}</Table.Td>
                        <Table.Td style={WRAP_ANYWHERE}>{formatLocation(edge.filePath, edge.line)}</Table.Td>
                        <Table.Td style={WRAP_ANYWHERE}>{edge.snippet ? <SnippetCode>{edge.snippet}</SnippetCode> : tr.common.none}</Table.Td>
                      </Table.Tr>
                    );
                  })}
                </Table.Tbody>
              </ScrollTable>
              <Pager page={page} size={pageDefaultSize} total={edges.length} onChange={setPage} />
            </Stack>
          )}
        </Stack>
      </Accordion.Panel>
    </Accordion.Item>
  );
}
