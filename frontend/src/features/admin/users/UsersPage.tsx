import { Button, Card, Group, Modal, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { useMe } from '../../../auth/session';
import { ROLE_OPTIONS, useChangeActive, useChangeRole, useUsers, type UserRole } from '../../../api/users';
import { EnabledBadge } from '../../../components/Badges';
import { ConfirmButton } from '../../../components/ConfirmButton';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { Pager } from '../../../components/Pager';
import { ScrollTable } from '../../../components/ScrollTable';
import { CELL_SELECT_MIN_WIDTH } from '../../../components/wrapStyles';
import { useUrlState } from '../../../hooks/useUrlState';
import { enumLabel } from '../../../i18n/enumLabel';
import { formatDateTime } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';
import { DirectorySearch } from './DirectorySearch';

/** Admin: application users, their roles and state; users are added from the LDAP directory. */
export function UsersPage() {
  const { params, page, update } = useUrlState();
  const q = params.get('q') ?? '';
  const users = useUsers(q, page);
  const changeRole = useChangeRole();
  const changeActive = useChangeActive();
  const me = useMe().data;
  const [selfChange, setSelfChange] = useState<{ id: number; role: UserRole } | null>(null);
  const columns = tr.admin.users.columns;

  return (
    <Stack>
      <Title order={2}>{tr.admin.users.title}</Title>
      <FilterForm key={q} initial={q} onSearch={(text) => update({ q: text })} />
      {users.data ? (
        users.data.items?.length ? (
          <>
            <ScrollTable highlightOnHover style={{ opacity: users.isPlaceholderData ? 0.5 : 1 }}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{columns.username}</Table.Th>
                  <Table.Th>{columns.name}</Table.Th>
                  <Table.Th>{columns.source}</Table.Th>
                  <Table.Th>{columns.role}</Table.Th>
                  <Table.Th>{columns.state}</Table.Th>
                  <Table.Th>{columns.lastLogin}</Table.Th>
                  <Table.Th>{columns.granted}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {users.data.items.map((user) => {
                  const busy = changeActive.isPending && changeActive.variables?.id === user.id;
                  return (
                  <Table.Tr key={user.id}>
                    <Table.Td>{user.username ?? tr.common.none}</Table.Td>
                    <Table.Td>{user.displayName ?? tr.common.none}</Table.Td>
                    <Table.Td>{enumLabel(tr.enums.userSource, user.source)}</Table.Td>
                    <Table.Td>
                      <Select
                        miw={CELL_SELECT_MIN_WIDTH}
                        aria-label={columns.role}
                        data={ROLE_OPTIONS}
                        allowDeselect={false}
                        value={user.role ?? null}
                        disabled={user.id == null || (changeRole.isPending && changeRole.variables?.id === user.id)}
                        onChange={(role) => {
                          if (user.id == null || !role) {
                            return;
                          }
                          // changing your own role may lock you out of these pages, so ask first
                          if (me?.username != null && user.username === me.username) {
                            setSelfChange({ id: user.id, role: role as UserRole });
                          } else {
                            changeRole.mutate({ id: user.id, role: role as UserRole });
                          }
                        }}
                      />
                    </Table.Td>
                    <Table.Td>
                      <Group gap="xs" wrap="nowrap">
                        <EnabledBadge enabled={user.active} />
                        {user.id != null && (user.active ? (
                          <ConfirmButton
                            label={tr.admin.users.deactivate}
                            message={tr.admin.users.deactivateConfirm(user.username ?? '')}
                            loading={busy}
                            onConfirm={() => changeActive.mutate({ id: user.id!, active: false })}
                          />
                        ) : (
                          <Button variant="light" loading={busy} onClick={() => changeActive.mutate({ id: user.id!, active: true })}>
                            {tr.admin.users.activate}
                          </Button>
                        ))}
                      </Group>
                    </Table.Td>
                    <Table.Td>{formatDateTime(user.lastLoginAt)}</Table.Td>
                    <Table.Td>{user.roleGrantedBy ?? tr.common.none}</Table.Td>
                  </Table.Tr>
                  );
                })}
              </Table.Tbody>
            </ScrollTable>
            <Pager page={page} size={users.data.size} total={users.data.total} onChange={(next) => update({ page: next })} />
          </>
        ) : (
          <Text>{tr.common.empty}</Text>
        )
      ) : users.isError ? (
        <ErrorView error={users.error} onRetry={() => void users.refetch()} />
      ) : (
        <Loading />
      )}
      <Modal opened={selfChange != null} onClose={() => setSelfChange(null)} title={tr.admin.common.confirmTitle}>
        <Text>{tr.admin.users.selfRoleConfirm(selfChange ? tr.header.roles[selfChange.role] : '')}</Text>
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={() => setSelfChange(null)}>{tr.admin.common.cancel}</Button>
          <Button onClick={() => {
            if (selfChange) {
              changeRole.mutate(selfChange);
            }
            setSelfChange(null);
          }}>{tr.admin.common.confirm}</Button>
        </Group>
      </Modal>
      <Card withBorder>
        <DirectorySearch />
      </Card>
    </Stack>
  );
}

function FilterForm({ initial, onSearch }: { initial: string; onSearch: (text: string) => void }) {
  const [text, setText] = useState(initial);
  return (
    <form onSubmit={(event) => { event.preventDefault(); onSearch(text.trim()); }}>
      <Group align="end">
        <TextInput style={{ flex: 1 }} label={tr.admin.users.query} value={text}
          onChange={(event) => setText(event.currentTarget.value)} />
        <Button type="submit">{tr.common.search}</Button>
      </Group>
    </form>
  );
}
