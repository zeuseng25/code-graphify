import { Button, Card, Group, Select, Stack, Switch, Title } from '@mantine/core';
import { useState } from 'react';
import { ErrorView } from '../../components/ErrorView';
import { useStartRun, type StartRequest } from '../../api/runs';
import { useScmConnections } from '../../api/scmConnections';
import { tr } from '../../i18n/tr';
import { RepositorySelect } from '../search/RepositorySelect';
import { StartRunError } from './StartRunError';

type Scope = NonNullable<StartRequest['scope']>;
const SCOPES: Scope[] = ['ALL', 'CONNECTION', 'REPOSITORY'];

/** Admins start a run here (web UI spec §4.2 row 9); a conflict links to the run in progress. */
export function StartRunForm() {
  const [scope, setScope] = useState<Scope>('ALL');
  const [id, setId] = useState<number | undefined>();
  const [force, setForce] = useState(false);
  const start = useStartRun();
  const connections = useScmConnections(scope === 'CONNECTION');
  const enabledConnections = (connections.data ?? []).filter((c) => c.enabled);

  return (
    <Card withBorder>
      <Stack>
        <Title order={4}>{tr.admin.runs.startTitle}</Title>
        <Group align="end" grow>
          <Select
            label={tr.admin.runs.scope}
            data={SCOPES.map((value) => ({ value, label: tr.enums.runScope[value] }))}
            value={scope}
            allowDeselect={false}
            onChange={(value) => { setScope((value ?? 'ALL') as Scope); setId(undefined); start.reset(); }}
          />
          {scope === 'CONNECTION' && (
            <Select
              label={tr.admin.runs.connection}
              placeholder={connections.isPending ? tr.common.loading : undefined}
              nothingFoundMessage={tr.admin.runs.noEnabledConnection}
              disabled={connections.isPending}
              data={enabledConnections.map((c) => ({ value: String(c.id), label: c.name ?? String(c.id) }))}
              value={id == null ? null : String(id)}
              onChange={(value) => { setId(value ? Number(value) : undefined); start.reset(); }}
            />
          )}
          {scope === 'REPOSITORY' && (
            <RepositorySelect label={tr.admin.runs.repository} placeholder={tr.admin.runs.repository} value={id}
              onChange={(value) => { setId(value); start.reset(); }} />
          )}
        </Group>
        <Switch label={tr.admin.runs.force} checked={force} onChange={(event) => { setForce(event.currentTarget.checked); start.reset(); }} />
        {scope === 'CONNECTION' && connections.isError && (
          <ErrorView error={connections.error} onRetry={() => void connections.refetch()} />
        )}
        {start.error && <StartRunError error={start.error} />}
        <Group>
          <Button
            loading={start.isPending}
            disabled={scope !== 'ALL' && id == null}
            onClick={() => start.mutate(scope === 'ALL' ? { scope, force } : { scope, id, force })}
          >
            {tr.admin.runs.start}
          </Button>
        </Group>
      </Stack>
    </Card>
  );
}
