import { Stack, Switch, Table, Text, Title } from '@mantine/core';
import { useImpactRules, useSaveImpactRule } from '../../../api/impactRules';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { ScrollTable } from '../../../components/ScrollTable';
import { enumLabel } from '../../../i18n/enumLabel';
import { tr } from '../../../i18n/tr';

/** Admin: per usage kind, whether the impact propagates and whether it is shown at level 1. */
export function ImpactRulesPage() {
  const rules = useImpactRules();
  const save = useSaveImpactRule();
  const columns = tr.admin.impactRules.columns;

  return (
    <Stack>
      <Title order={2}>{tr.admin.impactRules.title}</Title>
      <Text>{tr.admin.impactRules.intro}</Text>
      {rules.data ? (
        rules.data.length ? (
          <ScrollTable highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{columns.kind}</Table.Th>
                <Table.Th>{columns.propagates}</Table.Th>
                <Table.Th>{columns.shownAtLevel1}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rules.data.map((rule) => {
                const kind = rule.kind;
                const busy = save.isPending && save.variables?.kind === kind;
                const propagates = rule.propagates ?? false;
                const shownAtLevel1 = rule.shownAtLevel1 ?? false;
                return (
                  <Table.Tr key={kind}>
                    <Table.Td>{enumLabel(tr.enums.usageKind, kind)}</Table.Td>
                    <Table.Td>
                      <Switch aria-label={columns.propagates} checked={propagates} disabled={!kind || busy}
                        onChange={(event) => kind && save.mutate({ kind, propagates: event.currentTarget.checked, shownAtLevel1 })} />
                    </Table.Td>
                    <Table.Td>
                      <Switch aria-label={columns.shownAtLevel1} checked={shownAtLevel1} disabled={!kind || busy}
                        onChange={(event) => kind && save.mutate({ kind, propagates, shownAtLevel1: event.currentTarget.checked })} />
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </ScrollTable>
        ) : (
          <Text>{tr.common.empty}</Text>
        )
      ) : rules.isError ? (
        <ErrorView error={rules.error} onRetry={() => void rules.refetch()} />
      ) : (
        <Loading />
      )}
    </Stack>
  );
}
