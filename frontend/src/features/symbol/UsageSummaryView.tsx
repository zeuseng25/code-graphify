import { Accordion, Badge, Group, Stack, Table, Text, Title } from '@mantine/core';
import { useUsageSummary } from '../../api/symbols';
import { ErrorView } from '../../components/ErrorView';
import { Loading } from '../../components/Loading';
import { formatModulePath, formatNumber, formatRepository } from '../../i18n/format';
import { tr } from '../../i18n/tr';

/** How much the symbol is used: totals, then per repository, module and class. */
export function UsageSummaryView({ id }: { id: number }) {
  const summary = useUsageSummary(id);
  return (
    <section aria-label={tr.symbol.summary}>
      <Stack>
        <Title order={3}>{tr.symbol.summary}</Title>
        {!summary.data ? (
          summary.isError ? <ErrorView error={summary.error} onRetry={() => void summary.refetch()} /> : <Loading />
        ) : (
          <>
            <Text>{tr.symbol.summaryTotals(formatNumber(summary.data.usages), formatNumber(summary.data.repositories))}</Text>
            <Accordion multiple variant="separated">
              {(summary.data.byRepository ?? []).map((entry) => {
                const name = formatRepository(entry.repository);
                return (
                  <Accordion.Item key={entry.repository?.id ?? name} value={name}>
                    <Accordion.Control>
                      <Group justify="space-between" pr="md">
                        <Text span>{name}</Text>
                        <Badge variant="light">{formatNumber(entry.usages)}</Badge>
                      </Group>
                    </Accordion.Control>
                    <Accordion.Panel>
                      <Table>
                        <Table.Tbody>
                          {(entry.modules ?? []).map((module) => (
                            <Table.Tr key={module.modulePath}>
                              <Table.Td>
                                <Text fw={500}>{formatModulePath(module.modulePath)}</Text>
                                <Text size="sm" c="dimmed">{formatNumber(module.usages)}</Text>
                              </Table.Td>
                              <Table.Td>
                                <Stack gap={2}>
                                  {(module.classes ?? []).map((cls) => (
                                    <Group key={cls.classFqn} justify="space-between">
                                      <Text size="sm">{cls.classFqn}</Text>
                                      <Text size="sm" c="dimmed">{formatNumber(cls.usages)}</Text>
                                    </Group>
                                  ))}
                                </Stack>
                              </Table.Td>
                            </Table.Tr>
                          ))}
                        </Table.Tbody>
                      </Table>
                    </Accordion.Panel>
                  </Accordion.Item>
                );
              })}
            </Accordion>
          </>
        )}
      </Stack>
    </section>
  );
}
