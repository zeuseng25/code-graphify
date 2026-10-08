import { Alert, Anchor, Group, List, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { Fragment } from 'react';
import { Link } from 'react-router';
import { useRepoGraphReport } from '../../api/repoGraph';
import { EntryPointBadge } from '../../components/Badges';
import { ErrorView } from '../../components/ErrorView';
import { Fqn } from '../../components/Fqn';
import { Loading } from '../../components/Loading';
import { NO_WRAP, WRAP_ANYWHERE } from '../../components/wrapStyles';
import { formatDateTime, formatNumber } from '../../i18n/format';
import { tr } from '../../i18n/tr';
import { graphHref } from './graphParams';

/** The class name without its package; a nested class keeps its owner (`Outer$Inner` stays whole). */
function simpleName(fqn: string): string {
  return fqn.slice(fqn.lastIndexOf('.') + 1);
}

/** The package of a class, or empty for the default package. */
function packageOf(fqn: string): string {
  return fqn.includes('.') ? fqn.slice(0, fqn.lastIndexOf('.')) : '';
}

/** Counts, critical classes, communities and cycles of the last graph analysis, each linking into the graph. */
export function RepoGraphReportPanel({ id, includeExternal }: { id: number; includeExternal: boolean }) {
  const report = useRepoGraphReport(id);
  const data = report.data;
  const t = tr.graph.report;
  const methodHref = (fqn: string) => graphHref(id, { level: 'METHOD', focus: fqn, includeExternal });
  const classHref = (pkg: string) => graphHref(id, { level: 'CLASS', focus: pkg, includeExternal });

  return (
    // long package and class names wrap inside the panel instead of widening the page
    <section aria-label={t.title} style={WRAP_ANYWHERE}>
      <Stack gap="md">
        <Title order={3}>{t.title}</Title>
        {data ? (
          <>
            {!data.analyzedCommit && <Alert color="orange">{t.notAnalyzed}</Alert>}
            {data.stale && (
              <Alert color="orange">{t.stale(data.indexedCommit ?? tr.common.none, data.analyzedCommit ?? tr.common.none)}</Alert>
            )}
            <SimpleGrid cols={3}>
              {([
                [t.counts.modules, data.moduleCount], [t.counts.packages, data.packageCount],
                [t.counts.classes, data.classCount], [t.counts.dependencies, data.dependencyCount],
                [t.counts.communities, data.communityCount], [t.counts.entryPoints, data.entryPointCount],
              ] as const).map(([label, count]) => (
                <div key={label}>
                  <Text size="xs" c="dimmed">{label}</Text>
                  <Text fw={600}>{formatNumber(count)}</Text>
                </div>
              ))}
            </SimpleGrid>
            {data.analyzedAt && <Text size="sm" c="dimmed">{t.analyzedAt}: {formatDateTime(data.analyzedAt)}</Text>}

            <Title order={4}>{t.critical}</Title>
            {data.criticalClasses?.length ? (
              <Table>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t.criticalColumns.name}</Table.Th>
                    <Table.Th style={NO_WRAP}>{t.criticalColumns.dependents}</Table.Th>
                    <Table.Th style={NO_WRAP}>{t.criticalColumns.inOut}</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {data.criticalClasses?.map((item) => (
                    <Table.Tr key={item.fqn}>
                      <Table.Td style={WRAP_ANYWHERE}>
                        {item.fqn ? (
                          <>
                            <Group gap="xs">
                              <Anchor component={Link} to={methodHref(item.fqn)} title={item.fqn} aria-label={item.fqn}><Fqn value={simpleName(item.fqn)} /></Anchor>
                              {item.symbolId != null && (
                                <Anchor component={Link} to={`/symbols/${item.symbolId}`} size="sm">{t.openSymbol}</Anchor>
                              )}
                              {item.entryPoint && <EntryPointBadge />}
                            </Group>
                            {packageOf(item.fqn) && <Text size="xs" c="dimmed"><Fqn value={packageOf(item.fqn)} /></Text>}
                          </>
                        ) : tr.common.none}
                      </Table.Td>
                      <Table.Td style={NO_WRAP}>{formatNumber(item.dependents)}</Table.Td>
                      <Table.Td style={NO_WRAP}>{formatNumber(item.inDegree)} / {formatNumber(item.outDegree)}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            ) : (
              <Text>{t.noCritical}</Text>
            )}

            <Title order={4}>{t.communities}</Title>
            {data.communities?.length ? (
              <List>
                {data.communities?.map((community) => (
                  <List.Item key={community.id}>
                    {community.label ? <Fqn value={community.label} /> : tr.common.none} <Text span c="dimmed">{t.communitySize(formatNumber(community.size))}</Text>
                  </List.Item>
                ))}
              </List>
            ) : (
              <Text>{t.noCommunities}</Text>
            )}

            <Title order={4}>{t.cycles}</Title>
            {data.cycles?.length ? (
              <List>
                {data.cycles.map((cycle) => (
                  <List.Item key={cycle.id}>
                    {cycle.packages?.map((pkg, index) => (
                      <Fragment key={pkg}>
                        {index > 0 && t.cycleSeparator}
                        <Anchor component={Link} to={classHref(pkg)}><Fqn value={pkg} /></Anchor>
                      </Fragment>
                    ))}
                  </List.Item>
                ))}
              </List>
            ) : (
              <Text>{t.noCycles}</Text>
            )}

            <Title order={4}>{t.entryPoints}</Title>
            {data.entryPointClasses?.length ? (
              <List>
                {data.entryPointClasses?.map((fqn) => (
                  <List.Item key={fqn}><Anchor component={Link} to={methodHref(fqn)}><Fqn value={fqn} /></Anchor></List.Item>
                ))}
              </List>
            ) : (
              <Text>{t.noEntryPoints}</Text>
            )}
          </>
        ) : report.isError ? (
          <ErrorView error={report.error} onRetry={() => void report.refetch()} />
        ) : (
          <Loading />
        )}
      </Stack>
    </section>
  );
}
