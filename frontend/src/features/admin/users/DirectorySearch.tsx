import { Badge, Button, Group, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { ROLE_OPTIONS, useDirectorySearch, useRegisterUser, type UserRole } from '../../../api/users';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { ScrollTable } from '../../../components/ScrollTable';
import { CELL_SELECT_MIN_WIDTH } from '../../../components/wrapStyles';
import { tr } from '../../../i18n/tr';

/** Looks users up in the LDAP directory and registers them with a role. */
export function DirectorySearch() {
  const [text, setText] = useState('');
  const [submitted, setSubmitted] = useState('');
  const found = useDirectorySearch(submitted);
  const register = useRegisterUser();
  const [roles, setRoles] = useState<Record<string, UserRole>>({});

  return (
    <Stack>
      <Title order={3}>{tr.admin.users.directory}</Title>
      <form onSubmit={(event) => { event.preventDefault(); const next = text.trim();
        if (next === submitted && next !== '') {
          void found.refetch();
        }
        setSubmitted(next); }}>
        <Group align="end">
          <TextInput style={{ flex: 1 }} label={tr.admin.users.directoryQuery} value={text}
            onChange={(event) => setText(event.currentTarget.value)} />
          <Button type="submit">{tr.admin.users.directorySearch}</Button>
        </Group>
      </form>
      {submitted === '' ? null : found.data ? (
        found.data.length ? (
          <ScrollTable>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{tr.admin.users.columns.username}</Table.Th>
                <Table.Th>{tr.admin.users.columns.name}</Table.Th>
                <Table.Th>{tr.admin.users.columns.email}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {found.data.map((entry) => (
                <Table.Tr key={entry.username}>
                  <Table.Td>{entry.username ?? tr.common.none}</Table.Td>
                  <Table.Td>{entry.displayName ?? tr.common.none}</Table.Td>
                  <Table.Td>{entry.email ?? tr.common.none}</Table.Td>
                  <Table.Td>
                    {entry.appUserId != null ? (
                      <Badge variant="light">{tr.admin.users.registered}</Badge>
                    ) : (
                      <Group gap="xs" wrap="nowrap">
                        <Select
                          miw={CELL_SELECT_MIN_WIDTH}
                          aria-label={tr.admin.users.columns.role}
                          data={ROLE_OPTIONS}
                          allowDeselect={false}
                          value={roles[entry.username ?? ''] ?? 'USER'}
                          onChange={(role) => {
                            if (role) {
                              setRoles({ ...roles, [entry.username ?? '']: role as UserRole });
                            }
                          }}
                        />
                        <Button
                          loading={register.isPending && register.variables?.username === entry.username}
                          disabled={!entry.username}
                          onClick={() => register.mutate({ username: entry.username!, role: roles[entry.username!] ?? 'USER' })}
                        >
                          {tr.admin.users.add}
                        </Button>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </ScrollTable>
        ) : (
          <Text>{tr.common.empty}</Text>
        )
      ) : found.isError ? (
        <ErrorView error={found.error} onRetry={() => void found.refetch()} />
      ) : (
        <Loading />
      )}
    </Stack>
  );
}
