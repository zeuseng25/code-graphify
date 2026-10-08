import { Button, Group, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { useAudit } from '../../../api/audit';
import type { components } from '../../../api/schema';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { Pager } from '../../../components/Pager';
import { ScrollTable } from '../../../components/ScrollTable';
import { useUrlState } from '../../../hooks/useUrlState';
import { enumLabel } from '../../../i18n/enumLabel';
import { formatDateTime } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';

type AuditAction = components['schemas']['AuditAction'];
type ActionGroup = keyof typeof tr.admin.audit.actionGroups;

/**
 * Which group each action is offered under. Typed against the API's action list, so a new action fails the type check
 * until it is given a group (and, through the test, a Turkish name).
 */
export const ACTION_GROUP: Record<AuditAction, ActionGroup> = {
  BOOTSTRAP_ADMIN_CREATED: 'session', LOGIN_SUCCEEDED: 'session', LOGIN_FAILED: 'session', LOGOUT: 'session',
  PASSWORD_CHANGED: 'session', ACCOUNT_LOCKED: 'session',
  USER_REGISTERED: 'users', USER_ROLE_CHANGED: 'users', USER_ACTIVE_CHANGED: 'users',
  LDAP_CONFIG_UPDATED: 'connections', SCM_CONNECTION_CREATED: 'connections', SCM_CONNECTION_UPDATED: 'connections',
  SCM_CONNECTION_DELETED: 'connections', ARTIFACT_REPOSITORY_CREATED: 'connections',
  ARTIFACT_REPOSITORY_UPDATED: 'connections', ARTIFACT_REPOSITORY_DELETED: 'connections',
  SETTING_UPDATED: 'settings', ENTRY_POINT_ANNOTATION_CREATED: 'settings', ENTRY_POINT_ANNOTATION_UPDATED: 'settings',
  ENTRY_POINT_ANNOTATION_DELETED: 'settings', IMPACT_RULE_UPDATED: 'settings',
};

function knownAction(value: string | null): AuditAction | undefined {
  return value && Object.hasOwn(ACTION_GROUP, value) ? (value as AuditAction) : undefined;
}

/** The action picker's options: the groups in their declared order, each action under its Turkish name. */
const ACTION_OPTIONS = (Object.keys(tr.admin.audit.actionGroups) as ActionGroup[]).map((group) => ({
  group: tr.admin.audit.actionGroups[group],
  items: (Object.keys(ACTION_GROUP) as AuditAction[])
    .filter((action) => ACTION_GROUP[action] === group)
    .map((action) => ({ value: action, label: enumLabel(tr.enums.auditAction, action) })),
}));

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
    // a hand-edited link may name an unknown action; it is ignored rather than sent (the API refuses it)
    action: knownAction(params.get('action')),
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
                    <Table.Td title={entry.action}>{enumLabel(tr.enums.auditAction, entry.action)}</Table.Td>
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
        <TextInput label={tr.admin.audit.actor} placeholder={tr.admin.audit.actorHint} value={actor}
          onChange={(event) => setActor(event.currentTarget.value)} />
        <Select label={tr.admin.audit.action} placeholder={tr.admin.audit.anyAction} data={ACTION_OPTIONS}
          value={action || null} onChange={(value) => setAction(value ?? '')} searchable clearable miw={260} />
        <TextInput type="datetime-local" label={tr.admin.audit.from} value={from} onChange={(event) => setFrom(event.currentTarget.value)} />
        <TextInput type="datetime-local" label={tr.admin.audit.to} value={to} onChange={(event) => setTo(event.currentTarget.value)} />
        <Button type="submit">{tr.common.apply}</Button>
      </Group>
    </form>
  );
}
