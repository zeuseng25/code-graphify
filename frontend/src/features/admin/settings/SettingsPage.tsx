import { Button, Code, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { errorMessage } from '../../../api/errors';
import { useSaveSetting, useSettings, type Setting } from '../../../api/settings';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { ScrollTable } from '../../../components/ScrollTable';
import { useUrlState } from '../../../hooks/useUrlState';
import { enumLabel } from '../../../i18n/enumLabel';
import { formatDateTime } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';

/** Admin: the runtime settings, edited inline row by row. */
export function SettingsPage() {
  const { params, update } = useUrlState();
  const q = params.get('q') ?? '';
  const settings = useSettings();
  const needle = q.trim().toLowerCase();
  const columns = tr.admin.settings.columns;

  return (
    <Stack>
      <Title order={2}>{tr.admin.settings.title}</Title>
      <TextInput label={tr.admin.settings.filter} value={q} onChange={(event) => update({ q: event.currentTarget.value })} />
      {settings.data ? (
        <ScrollTable>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{columns.key}</Table.Th>
              <Table.Th>{columns.value}</Table.Th>
              <Table.Th>{columns.type}</Table.Th>
              <Table.Th>{columns.description}</Table.Th>
              <Table.Th>{columns.updated}</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {settings.data
              .filter((s) => !needle || (s.key ?? '').toLowerCase().includes(needle)
                || (s.description ?? '').toLowerCase().includes(needle))
              .map((setting) => <SettingRow key={`${setting.key}-${setting.updatedAt ?? ''}`} setting={setting} />)}
          </Table.Tbody>
        </ScrollTable>
      ) : settings.isError ? (
        <ErrorView error={settings.error} onRetry={() => void settings.refetch()} />
      ) : (
        <Loading />
      )}
    </Stack>
  );
}

function formatHintOf(setting: Setting, draft: string): string | null {
  if (setting.type === 'INT' && !/^-?\d+$/.test(draft)) {
    return tr.admin.settings.notInteger;
  }
  if (setting.type === 'BOOL' && draft !== 'true' && draft !== 'false') {
    return tr.admin.settings.notBool;
  }
  return null;
}

function SettingRow({ setting }: { setting: Setting }) {
  const [draft, setDraft] = useState(setting.value ?? '');
  const save = useSaveSetting(setting.key ?? '');
  const formatHint = formatHintOf(setting, draft);
  const error = formatHint ?? (save.error ? errorMessage(save.error) : null);
  const hasRange = setting.minValue != null || setting.maxValue != null;

  return (
    <Table.Tr>
      <Table.Td><Code>{setting.key}</Code></Table.Td>
      <Table.Td>
        <TextInput aria-label={setting.key} value={draft} error={error}
          onChange={(event) => { save.reset(); setDraft(event.currentTarget.value); }} />
      </Table.Td>
      <Table.Td>
        {enumLabel(tr.enums.settingType, setting.type)}
        {hasRange && (
          <Text size="xs" c="dimmed">
            {tr.admin.settings.range(String(setting.minValue ?? tr.common.none), String(setting.maxValue ?? tr.common.none))}
          </Text>
        )}
      </Table.Td>
      <Table.Td>{setting.description ?? tr.common.none}</Table.Td>
      <Table.Td>
        <Text size="sm">
          {setting.updatedAt || setting.updatedBy
            ? tr.admin.common.updated(setting.updatedBy ?? tr.common.none, formatDateTime(setting.updatedAt))
            : tr.common.none}
        </Text>
      </Table.Td>
      <Table.Td>
        <Button loading={save.isPending} disabled={draft === (setting.value ?? '') || formatHint !== null}
          onClick={() => save.mutate(draft)}>
          {tr.admin.common.save}
        </Button>
      </Table.Td>
    </Table.Tr>
  );
}
