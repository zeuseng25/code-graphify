import { Button, Code, Group, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { useAudit } from '../../../api/audit';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { Pager } from '../../../components/Pager';
import { ScrollTable } from '../../../components/ScrollTable';
import { useUrlState } from '../../../hooks/useUrlState';
import { formatDateTime } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';

// The backend filters at `at < to`; the input has minute precision, so the chosen minute is included whole
// by sending its last millisecond (a minute is 60 000 ms, minus one).
const MINUTE_END_OFFSET_MILLIS = 59_999;

/** An instant as the local "YYYY-MM-DDTHH:mm" a datetime-local input takes; unparseable values give an empty input. */
function toLocal(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** The instant of a local minute; `endOfMinute` gives its last millisecond. */
function toInstant(local: string, endOfMinute = false): string {
  const date = new Date(local);
  if (!local || Number.isNaN(date.getTime())) {
    return '';
  }
  return new Date(date.getTime() + (endOfMinute ? MINUTE_END_OFFSET_MILLIS : 0)).toISOString();
}

/** Admin: the audit log of administrative changes, filtered through the URL. */
export function AuditPage() {
  const { params, page, update } = useUrlState();
  const filters = {
    actor: params.get('actor') || undefined,
    action: params.get('action') || undefined,
    from: params.get('from') || undefined,
    to: params.get('to') || undefined,
  };
  const audit = useAudit(filters, page);
  const columns = tr.admin.audit.columns;

  return (
    <Stack>
      <Title order={2}>{tr.admin.audit.title}</Title>
      <FilterForm key={params.toString()} initial={filters}
        onApply={(next) => update(next)} />
      {audit.data ? (
        audit.data.items?.length ? (
          <>
            <ScrollTable highlightOnHover style={{ opacity: audit.isPlaceholderData ? 0.5 : 1 }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{columns.at}</Table.Th>
                  <Table.Th>{columns.actor}</Table.Th>
                  <Table.Th>{columns.action}</Table.Th>
                  <Table.Th>{columns.target}</Table.Th>
                  <Table.Th>{columns.details}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {audit.data.items.map((entry) => (
                  <Table.Tr key={entry.id}>
                    <Table.Td>{formatDateTime(entry.at)}</Table.Td>
                    <Table.Td>{entry.actor ?? tr.common.none}</Table.Td>
                    <Table.Td><Code>{entry.action ?? tr.common.none}</Code></Table.Td>
                    <Table.Td>{entry.target ?? tr.common.none}</Table.Td>
                    <Table.Td>{entry.details ?? tr.common.none}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </ScrollTable>
            <Pager page={page} size={audit.data.size} total={audit.data.total} onChange={(next) => update({ page: next })} />
          </>
        ) : (
          <Text>{tr.common.empty}</Text>
        )
      ) : audit.isError ? (
        <ErrorView error={audit.error} onRetry={() => void audit.refetch()} />
      ) : (
        <Loading />
      )}
    </Stack>
  );
}

function FilterForm({ initial, onApply }: {
  initial: { actor?: string; action?: string; from?: string; to?: string };
  onApply: (filters: Record<'actor' | 'action' | 'from' | 'to', string>) => void;
}) {
  const [actor, setActor] = useState(initial.actor ?? '');
  const [action, setAction] = useState(initial.action ?? '');
  const [from, setFrom] = useState(initial.from ? toLocal(initial.from) : '');
  const [to, setTo] = useState(initial.to ? toLocal(initial.to) : '');
  return (
    <form onSubmit={(event) => {
      event.preventDefault();
      onApply({ actor: actor.trim(), action: action.trim(), from: toInstant(from), to: toInstant(to, true) });
    }}>
      <Group align="end">
        <TextInput label={tr.admin.audit.actor} value={actor} onChange={(event) => setActor(event.currentTarget.value)} />
        <TextInput label={tr.admin.audit.action} value={action} onChange={(event) => setAction(event.currentTarget.value)} />
        <TextInput type="datetime-local" label={tr.admin.audit.from} value={from} onChange={(event) => setFrom(event.currentTarget.value)} />
        <TextInput type="datetime-local" label={tr.admin.audit.to} value={to} onChange={(event) => setTo(event.currentTarget.value)} />
        <Button type="submit">{tr.common.apply}</Button>
      </Group>
    </form>
  );
}
