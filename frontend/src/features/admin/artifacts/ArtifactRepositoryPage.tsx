import { Alert, Button, Group, NumberInput, Stack, Switch, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import {
  useArtifactRepository, useDeleteArtifactRepository, useSaveArtifactRepository, useTestArtifactRepository,
  type ArtifactRepositoryView,
} from '../../../api/artifactRepositories';
import { errorMessage } from '../../../api/errors';
import { SyncStatusBadge } from '../../../components/Badges';
import { ConfirmButton } from '../../../components/ConfirmButton';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { KEEP_SECRET, mavenTargetChanged, secretNeedsReentry, secretPayload, type SecretState } from '../../../components/secret';
import { SecretField } from '../../../components/SecretField';
import { formatDateTime } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';
import { NotFoundPage } from '../../../pages/NotFoundPage';

/** Admin: create (`new`) or edit one Maven repository. */
export function ArtifactRepositoryPage() {
  const param = useParams().id;
  const id = param === 'new' ? null : Number(param);
  if (id !== null && (!Number.isInteger(id) || id <= 0)) {
    return <NotFoundPage />;
  }
  return <ArtifactRepositoryLoader id={id} />;
}

function ArtifactRepositoryLoader({ id }: { id: number | null }) {
  const result = useArtifactRepository(id);
  if (id === null) {
    return <ArtifactRepositoryForm key="new" view={undefined} />;
  }
  const view = result.data;
  if (!view) {
    return result.isError ? <ErrorView error={result.error} onRetry={() => void result.refetch()} /> : <Loading />;
  }
  return <ArtifactRepositoryForm key={view.id ?? 'new'} view={view} />;
}

interface FormValues {
  name: string;
  url: string;
  username: string;
  mirrorOf: string;
  sortOrder: number;
  enabled: boolean;
}

function valuesOf(view: ArtifactRepositoryView | undefined): FormValues {
  return {
    name: view?.name ?? '',
    url: view?.url ?? '',
    username: view?.username ?? '',
    mirrorOf: view?.mirrorOf ?? '',
    sortOrder: view?.sortOrder ?? 0,
    enabled: view?.enabled ?? true,
  };
}

function ArtifactRepositoryForm({ view }: { view: ArtifactRepositoryView | undefined }) {
  const navigate = useNavigate();
  const fields = tr.admin.artifacts.fields;
  const form = useForm<FormValues>({ mode: 'controlled', initialValues: valuesOf(view),
    validate: {
      name: (value) => (value.trim() ? null : tr.errors.required),
      sortOrder: (value) => (Number.isInteger(value) && value >= 0 ? null : tr.errors.required),
      url: (value) => (value.trim() ? null : tr.errors.required),
    },
  });
  const [secret, setSecret] = useState<SecretState>(KEEP_SECRET);
  const save = useSaveArtifactRepository(view?.id ?? null);
  const test = useTestArtifactRepository(view?.id ?? 0);
  const remove = useDeleteArtifactRepository(view?.id ?? 0);
  const values = form.getValues();

  const targetChanged = view != null && mavenTargetChanged(values, { url: view.url, username: view.username });
  const needsReentry = secretNeedsReentry(!!view?.secretSet, targetChanged, secret);
  const unsaved = form.isDirty() || secret.mode !== 'keep';

  const submit = (formValues: FormValues) => {
    save.mutate(
      {
        ...formValues,
        username: formValues.username || undefined,
        mirrorOf: formValues.mirrorOf || undefined,
        secret: secretPayload(secret),
      },
      {
        onSuccess: (saved) => {
          setSecret(KEEP_SECRET);
          form.setInitialValues(valuesOf(saved));
          form.reset();
          save.reset(); // drops the typed secret held in the mutation's variables
          if (view == null && saved?.id != null) {
            void navigate(`/admin/artifact-repositories/${saved.id}`, { replace: true });
          }
        },
      },
    );
  };

  return (
    <form onSubmit={form.onSubmit(submit)}>
      <Stack maw={640}>
        <Title order={2}>{view ? view.name ?? '' : tr.admin.artifacts.newTitle}</Title>
        <TextInput label={fields.name} {...form.getInputProps('name')} />
        <TextInput label={fields.url} description={tr.admin.artifacts.hints.url} {...form.getInputProps('url')} />
        <TextInput label={fields.username} {...form.getInputProps('username')} />
        <SecretField label={fields.secret} stored={!!view?.secretSet} state={secret} onChange={setSecret}
          reentry={needsReentry ? tr.admin.artifacts.secretTarget : undefined} />
        <TextInput label={fields.mirrorOf} description={tr.admin.artifacts.hints.mirrorOf} {...form.getInputProps('mirrorOf')} />
        <NumberInput label={fields.sortOrder} min={0} allowDecimal={false} {...form.getInputProps('sortOrder')} />
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
              <ConfirmButton label={tr.admin.common.delete} message={tr.admin.artifacts.deleteConfirm(view.name ?? '')}
                loading={remove.isPending}
                onConfirm={() => remove.mutate(undefined, { onSuccess: () => void navigate('/admin/artifact-repositories') })} />
            </>
          )}
          <Button component={Link} to="/admin/artifact-repositories" variant="subtle">{tr.admin.common.back}</Button>
        </Group>
        {view?.id != null && unsaved && <Text size="sm" c="dimmed">{tr.admin.common.testUnsaved}</Text>}
        {view?.id != null && (
          <Group gap="xs">
            <Text size="sm">{tr.admin.common.lastTest}:</Text>
            <SyncStatusBadge value={view.lastTestStatus} />
            <Text size="sm">{formatDateTime(view.lastTestAt)}</Text>
          </Group>
        )}
      </Stack>
    </form>
  );
}
