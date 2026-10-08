import { Alert, Button, Group, Stack, Switch, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { errorMessage } from '../../../api/errors';
import { useLdapConfig, useSaveLdapConfig, useTestLdapConfig, type LdapConfigView } from '../../../api/ldap';
import { ErrorView } from '../../../components/ErrorView';
import { Loading } from '../../../components/Loading';
import { KEEP_SECRET, ldapTargetChanged, secretNeedsReentry, secretPayload, type SecretState } from '../../../components/secret';
import { SecretField } from '../../../components/SecretField';
import { formatDateTime } from '../../../i18n/format';
import { tr } from '../../../i18n/tr';

/** Admin: the LDAP login settings (a single record). */
export function LdapPage() {
  const result = useLdapConfig();
  const view = result.data;
  if (!view) {
    return result.isError ? <ErrorView error={result.error} onRetry={() => void result.refetch()} /> : <Loading />;
  }
  return <LdapForm key={view.updatedAt ?? 'none'} view={view} />;
}

interface FormValues {
  enabled: boolean;
  url: string;
  baseDn: string;
  userSearchBase: string;
  userSearchFilter: string;
  userQueryFilter: string;
  usernameAttr: string;
  displayNameAttr: string;
  emailAttr: string;
  bindDn: string;
}

function valuesOf(view: LdapConfigView): FormValues {
  return {
    enabled: view.enabled ?? false,
    url: view.url ?? '',
    baseDn: view.baseDn ?? '',
    userSearchBase: view.userSearchBase ?? '',
    userSearchFilter: view.userSearchFilter ?? '',
    userQueryFilter: view.userQueryFilter ?? '',
    usernameAttr: view.usernameAttr ?? '',
    displayNameAttr: view.displayNameAttr ?? '',
    emailAttr: view.emailAttr ?? '',
    bindDn: view.bindDn ?? '',
  };
}

const TEXT_FIELDS = ['url', 'baseDn', 'userSearchBase', 'userSearchFilter', 'userQueryFilter', 'usernameAttr',
  'displayNameAttr', 'emailAttr', 'bindDn'] as const;

function LdapForm({ view }: { view: LdapConfigView }) {
  const fields = tr.admin.ldap.fields;
  const hints: Partial<Record<(typeof TEXT_FIELDS)[number], string>> = tr.admin.ldap.hints;
  const form = useForm<FormValues>({ mode: 'controlled', initialValues: valuesOf(view) });
  const [secret, setSecret] = useState<SecretState>(KEEP_SECRET);
  const save = useSaveLdapConfig();
  const test = useTestLdapConfig();
  const values = form.getValues();

  const targetChanged = ldapTargetChanged(values, { url: view.url, bindDn: view.bindDn });
  const needsReentry = secretNeedsReentry(!!view.bindPasswordSet, targetChanged, secret);
  const payload = (formValues: FormValues) => ({ ...formValues, bindPassword: secretPayload(secret) });

  return (
    <form onSubmit={form.onSubmit((formValues) => save.mutate(payload(formValues), {
      onSuccess: () => {
        setSecret(KEEP_SECRET);
        save.reset(); // drops the typed bind password held in the mutation's variables
      },
    }))}>
      <Stack maw={640}>
        <Title order={2}>{tr.admin.ldap.title}</Title>
        <Switch label={fields.enabled} {...form.getInputProps('enabled', { type: 'checkbox' })} />
        {TEXT_FIELDS.map((name) => (
          <TextInput key={name} label={fields[name]} description={hints[name]} {...form.getInputProps(name)} />
        ))}
        <SecretField label={fields.bindPassword} stored={!!view.bindPasswordSet} state={secret} onChange={setSecret}
          reentry={needsReentry ? tr.admin.ldap.secretTarget : undefined} />
        {save.isError && <Alert color="red">{errorMessage(save.error)}</Alert>}
        <Group>
          <Button type="submit" loading={save.isPending} disabled={needsReentry}>{tr.admin.common.save}</Button>
          <Button type="button" variant="light" loading={test.isPending} disabled={needsReentry}
            onClick={() => test.mutate(payload(form.getValues()), { onSuccess: () => test.reset() })}>
            {tr.admin.common.test}
          </Button>
          <Text size="sm" c="dimmed">{tr.admin.ldap.testHint}</Text>
        </Group>
        <Text size="sm" c="dimmed">
          {tr.admin.common.updated(view.updatedBy ?? tr.common.none, formatDateTime(view.updatedAt))}
        </Text>
      </Stack>
    </form>
  );
}
