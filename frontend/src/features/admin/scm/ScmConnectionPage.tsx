import { Alert, Button, Group, Select, Stack, Switch, TagsInput, Text, Textarea, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { errorMessage } from '../../../api/errors';
import {
  useDeleteScmConnection, useSaveScmConnection, useScmConnection, useTestScmConnection, type ScmConnectionUpdate, type ScmConnectionView,
} from '../../../api/scmConnections';
import { StartRunError } from '../../runs/StartRunError';
import { useStartRun } from '../../../api/runs';
import { SyncStatusBadge } from '../../../components/Badges';
import { ConfirmButton } from '../../../components/ConfirmButton';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { KEEP_SECRET, scmTargetChanged, secretNeedsReentry, secretPayload, type SecretState } from '../../../components/secret';
import { SecretField } from '../../../components/SecretField';
import { formatDateTime } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';
import { NotFoundPage } from '../../../pages/NotFoundPage';

type ScmType = NonNullable<ScmConnectionView['type']>;
const SCM_TYPES = Object.keys(tr.enums.scmType) as ScmType[];

/** Admin: create (`new`) or edit one repo connection (Bitbucket, GitHub or Git). */
export function ScmConnectionPage() {
  const param = useParams().id;
  const id = param === 'new' ? null : Number(param);
  if (id !== null && (!Number.isInteger(id) || id <= 0)) {
    return <NotFoundPage />;
  }
  return <ScmConnectionLoader id={id} />;
}

function ScmConnectionLoader({ id }: { id: number | null }) {
  const result = useScmConnection(id);
  if (id === null) {
    return <ScmConnectionForm key="new" view={undefined} />;
  }
  const view = result.data;
  if (!view) {
    return result.isError ? <ErrorView error={result.error} onRetry={() => void result.refetch()} /> : <Loading />;
  }
  return <ScmConnectionForm key={view.id ?? 'new'} view={view} />;
}

export interface FormValues {
  name: string;
  type: ScmType;
  baseUrl: string;
  username: string;
  includeProjects: string[];
  excludeRepos: string[];
  /** One clone URL per line (Git only). */
  repositoryUrls: string;
  /** GitHub only: also list the repositories the token's owner owns. */
  includeOwnRepositories: boolean;
  enabled: boolean;
}

function urlLines(text: string): string[] {
  return text.split('\n').map((line) => line.trim()).filter(Boolean);
}

/** The request for the chosen type: fields the type does not use are sent empty (the backend rejects them). */
export function connectionBody(values: FormValues, secret: string | undefined): ScmConnectionUpdate {
  const urls = urlLines(values.repositoryUrls);
  const git = values.type === 'GIT';
  return {
    name: values.name, type: values.type, baseUrl: values.baseUrl, username: values.username || undefined, secret,
    enabled: values.enabled,
    includeProjects: git ? [] : values.includeProjects,
    excludeRepos: git ? [] : values.excludeRepos,
    repositoryUrls: git ? urls : [],
    includeOwnRepositories: values.type === 'GITHUB' && values.includeOwnRepositories,
  };
}

function usernameHintFor(type: ScmType) {
  const hints = tr.admin.scm.hints;
  return type === 'GITHUB' ? hints.usernameGithub : type === 'GIT' ? hints.usernameGit : undefined;
}

function valuesOf(view: ScmConnectionView | undefined): FormValues {
  return {
    name: view?.name ?? '',
    type: view?.type ?? 'BITBUCKET_DC',
    baseUrl: view?.baseUrl ?? '',
    username: view?.username ?? '',
    includeProjects: view?.includeProjects ?? [],
    excludeRepos: view?.excludeRepos ?? [],
    repositoryUrls: (view?.repositoryUrls ?? []).join('\n'),
    includeOwnRepositories: view?.includeOwnRepositories ?? false,
    enabled: view?.enabled ?? true,
  };
}

function ScmConnectionForm({ view }: { view: ScmConnectionView | undefined }) {
  const navigate = useNavigate();
  const fields = tr.admin.scm.fields;
  const hints = tr.admin.scm.hints;
  const form = useForm<FormValues>({ mode: 'controlled', initialValues: valuesOf(view),
    validate: {
      name: (value) => (value.trim() ? null : tr.errors.required),
      baseUrl: (value) => (value.trim() ? null : tr.errors.required),
      includeProjects: (value, all) => (all.type === 'GITHUB' && value.length === 0 && !all.includeOwnRepositories
        ? tr.admin.scm.githubScope : null),
      repositoryUrls: (value, all) => (all.type === 'GIT' && urlLines(value).length === 0 ? tr.errors.required : null),
    },
  });
  const [secret, setSecret] = useState<SecretState>(KEEP_SECRET);
  const save = useSaveScmConnection(view?.id ?? null);
  const test = useTestScmConnection(view?.id ?? 0);
  const remove = useDeleteScmConnection(view?.id ?? 0);
  const scan = useStartRun();
  const values = form.getValues();

  const targetChanged = view != null && scmTargetChanged(values, { baseUrl: view.baseUrl, username: view.username });
  const needsReentry = secretNeedsReentry(!!view?.secretSet, targetChanged, secret);
  const unsaved = form.isDirty() || secret.mode !== 'keep';

  const submit = (formValues: FormValues) => {
    save.mutate(
      connectionBody(formValues, secretPayload(secret)),
      {
        onSuccess: (saved) => {
          setSecret(KEEP_SECRET);
          form.setInitialValues(valuesOf(saved));
          form.reset();
          save.reset(); // drops the typed token held in the mutation's variables
          if (view == null && saved?.id != null) {
            void navigate(`/admin/scm-connections/${saved.id}`, { replace: true });
          }
        },
      },
    );
  };

  return (
    <form onSubmit={form.onSubmit(submit)}>
      <Stack maw={640}>
        <Title order={2}>{view ? view.name ?? '' : tr.admin.scm.newTitle}</Title>
        <TextInput label={fields.name} {...form.getInputProps('name')} />
        {view ? (
          <TextInput label={fields.type} description={tr.admin.scm.typeFixed} readOnly
            value={tr.enums.scmType[values.type]} />
        ) : (
          <Select label={fields.type} allowDeselect={false}
            data={SCM_TYPES.map((value) => ({ value, label: tr.enums.scmType[value] }))}
            {...form.getInputProps('type')}
            onChange={(value) => {
              form.setFieldValue('type', (value ?? 'BITBUCKET_DC') as ScmType);
              // fields the new type does not use are not carried over
              form.setFieldValue('includeProjects', []);
              form.setFieldValue('excludeRepos', []);
              form.setFieldValue('repositoryUrls', '');
              form.setFieldValue('includeOwnRepositories', false);
              // errors of the previous type's fields do not apply to the new one
              form.clearErrors();
            }} />
        )}
        <TextInput label={fields.baseUrl} description={hints.baseUrl[values.type]}
          {...form.getInputProps('baseUrl')} />
        <TextInput label={fields.username} description={usernameHintFor(values.type)}
          {...form.getInputProps('username')} />
        <SecretField label={fields.secret} description={hints.secret[values.type]} stored={!!view?.secretSet}
          state={secret} onChange={setSecret} reentry={needsReentry ? tr.admin.scm.secretTarget : undefined} />
        {values.type === 'BITBUCKET_DC' && (
          <TagsInput label={fields.includeProjects} description={hints.includeProjects}
            {...form.getInputProps('includeProjects')} />
        )}
        {values.type === 'GITHUB' && (
          <>
            <TagsInput label={fields.organizations} description={hints.organizations}
              {...form.getInputProps('includeProjects')} />
            <Switch label={fields.includeOwnRepositories} description={hints.includeOwnRepositories}
              {...form.getInputProps('includeOwnRepositories', { type: 'checkbox' })} />
          </>
        )}
        {values.type !== 'GIT' && (
          <TagsInput label={fields.excludeRepos} description={hints.excludeRepos[values.type]}
            {...form.getInputProps('excludeRepos')} />
        )}
        {values.type === 'GIT' && (
          <Textarea label={fields.repositoryUrls} description={hints.repositoryUrls} autosize minRows={3}
            {...form.getInputProps('repositoryUrls')} />
        )}
        <Switch label={fields.enabled} {...form.getInputProps('enabled', { type: 'checkbox' })} />
        {save.isError && <Alert color="red">{errorMessage(save.error)}</Alert>}
        <Group>
          <Button type="submit" loading={save.isPending} disabled={needsReentry}>{tr.admin.common.save}</Button>
          {view?.id != null && (
            <>
              <Button type="button" variant="light" loading={test.isPending} disabled={unsaved}
                onClick={() => test.mutate()}>
                {tr.admin.common.test}
              </Button>
              {view.enabled && (
                <Button type="button" variant="light" loading={scan.isPending}
                  onClick={() => scan.mutate({ scope: 'CONNECTION', id: view.id, force: false })}>
                  {tr.admin.runs.scanConnection}
                </Button>
              )}
              <ConfirmButton label={tr.admin.common.delete} message={tr.admin.scm.deleteConfirm(view.name ?? '')}
                loading={remove.isPending}
                onConfirm={() => remove.mutate(undefined, { onSuccess: () => void navigate('/admin/scm-connections') })} />
            </>
          )}
          <Button component={Link} to="/admin/scm-connections" variant="subtle">{tr.admin.common.back}</Button>
        </Group>
        {scan.error && <StartRunError error={scan.error} />}
        {view?.id != null && unsaved && <Text size="sm" c="dimmed">{tr.admin.common.testUnsaved}</Text>}
        {view?.id != null && (
          <Stack gap={4}>
            <Group gap="xs">
              <Text size="sm">{tr.admin.common.lastTest}:</Text>
              <SyncStatusBadge value={view.lastTestStatus} />
              <Text size="sm">{formatDateTime(view.lastTestAt)}</Text>
            </Group>
            <Group gap="xs">
              <Text size="sm">{tr.admin.common.lastSync}:</Text>
              <SyncStatusBadge value={view.lastSyncStatus} />
              <Text size="sm">{formatDateTime(view.lastSyncAt)}</Text>
            </Group>
            {view.lastSyncError && <Text size="sm" c="red">{tr.admin.scm.syncError}: {view.lastSyncError}</Text>}
          </Stack>
        )}
      </Stack>
    </form>
  );
}
