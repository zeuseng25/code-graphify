import { Alert, Button, Card, Code, Group, Modal, Stack, Switch, Table, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { errorMessage } from '../../../api/errors';
import {
  useDeleteEntryPoint, useEntryPoints, useSaveEntryPoint, type EntryPointAnnotation,
} from '../../../api/impactRules';
import { ConfirmButton } from '../../../components/ConfirmButton';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { ScrollTable } from '../../../components/ScrollTable';
import { tr } from '../../../i18n/tr';

/** Admin: the annotations whose bearers are shown as entry points in an impact analysis. */
export function EntryPointsPage() {
  const entries = useEntryPoints();
  const save = useSaveEntryPoint(null);
  const remove = useDeleteEntryPoint();
  const [editing, setEditing] = useState<EntryPointAnnotation | null>(null);
  const columns = tr.admin.entryPoints.columns;

  return (
    <Stack>
      <Title order={2}>{tr.admin.entryPoints.title}</Title>
      <Text>{tr.admin.entryPoints.intro}</Text>
      {entries.data ? (
        entries.data.length ? (
          <ScrollTable highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{columns.annotationFqn}</Table.Th>
                <Table.Th>{columns.label}</Table.Th>
                <Table.Th>{columns.enabled}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {entries.data.map((entry) => (
                <EntryRow key={entry.id} entry={entry} deleting={remove.isPending && remove.variables === entry.id}
                  onEdit={() => setEditing(entry)} onDelete={(id) => remove.mutate(id)} />
              ))}
            </Table.Tbody>
          </ScrollTable>
        ) : (
          <Text>{tr.common.empty}</Text>
        )
      ) : entries.isError ? (
        <ErrorView error={entries.error} onRetry={() => void entries.refetch()} />
      ) : (
        <Loading />
      )}
      <Card withBorder>
        <AddForm save={save} />
      </Card>
      {editing && <EditModal key={editing.id} entry={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function EntryRow({ entry, deleting, onEdit, onDelete }: {
  entry: EntryPointAnnotation;
  deleting: boolean;
  onEdit: () => void;
  onDelete: (id: number) => void;
}) {
  const save = useSaveEntryPoint(entry.id ?? null);
  const busy = save.isPending || deleting;
  const fqn = entry.annotationFqn ?? '';
  return (
    <Table.Tr>
      <Table.Td><Code>{fqn}</Code></Table.Td>
      <Table.Td>{entry.label ?? tr.common.none}</Table.Td>
      <Table.Td>
        <Switch aria-label={tr.admin.entryPoints.columns.enabled} checked={entry.enabled ?? false} disabled={entry.id == null || busy}
          onChange={(event) => save.mutate({ annotationFqn: fqn, label: entry.label, enabled: event.currentTarget.checked })} />
      </Table.Td>
      <Table.Td>
        <Group gap="xs" justify="flex-end" wrap="nowrap">
          <Button variant="default" disabled={busy} onClick={onEdit}>{tr.admin.common.edit}</Button>
          {entry.id != null && (
            <ConfirmButton label={tr.admin.common.delete} message={tr.admin.entryPoints.deleteConfirm(fqn)}
              loading={deleting} disabled={busy} onConfirm={() => onDelete(entry.id!)} />
          )}
        </Group>
      </Table.Td>
    </Table.Tr>
  );
}

function AddForm({ save }: { save: ReturnType<typeof useSaveEntryPoint> }) {
  const [fqn, setFqn] = useState('');
  const [label, setLabel] = useState('');
  return (
    <form onSubmit={(event) => {
      event.preventDefault();
      save.mutate({ annotationFqn: fqn.trim(), label: label.trim() || undefined, enabled: true }, {
        onSuccess: () => { setFqn(''); setLabel(''); },
      });
    }}>
      <Stack>
      <Group align="end">
        <TextInput style={{ flex: 2 }} label={tr.admin.entryPoints.fields.annotationFqn} value={fqn}
          onChange={(event) => { setFqn(event.currentTarget.value); save.reset(); }} />
        <TextInput style={{ flex: 1 }} label={tr.admin.entryPoints.fields.label} value={label}
          onChange={(event) => { setLabel(event.currentTarget.value); save.reset(); }} />
        <Button type="submit" loading={save.isPending}>{tr.admin.common.create}</Button>
      </Group>
      {save.isError && <Alert color="red">{errorMessage(save.error)}</Alert>}
      </Stack>
    </form>
  );
}

function EditModal({ entry, onClose }: { entry: EntryPointAnnotation; onClose: () => void }) {
  const save = useSaveEntryPoint(entry.id ?? null);
  const [fqn, setFqn] = useState(entry.annotationFqn ?? '');
  const [label, setLabel] = useState(entry.label ?? '');
  return (
    <Modal opened onClose={onClose} title={tr.admin.common.edit}>
      <form onSubmit={(event) => {
        event.preventDefault();
        save.mutate({ annotationFqn: fqn.trim(), label: label.trim() || undefined, enabled: entry.enabled }, { onSuccess: onClose });
      }}>
        <Stack>
          <TextInput label={tr.admin.entryPoints.fields.annotationFqn} value={fqn}
            onChange={(event) => { setFqn(event.currentTarget.value); save.reset(); }} />
          <TextInput label={tr.admin.entryPoints.fields.label} value={label}
            onChange={(event) => { setLabel(event.currentTarget.value); save.reset(); }} />
          {save.isError && <Alert color="red">{errorMessage(save.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>{tr.admin.common.cancel}</Button>
            <Button type="submit" loading={save.isPending}>{tr.admin.common.save}</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
