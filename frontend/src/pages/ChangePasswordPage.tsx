import { Alert, Button, Paper, PasswordInput, Stack, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router';
import { errorMessage } from '../api/errors';
import { useChangePassword, useMe } from '../auth/session';
import { tr } from '../i18n/tr';

export function ChangePasswordPage() {
  const me = useMe().data;
  const change = useChangePassword();
  const navigate = useNavigate();
  const form = useForm({
    initialValues: { currentPassword: '', newPassword: '', confirm: '' },
    validate: {
      currentPassword: (value) => (value ? null : tr.errors.required),
      newPassword: (value) => (value ? null : tr.errors.required),
      confirm: (value, values) => (value === values.newPassword ? null : tr.changePassword.mismatch),
    },
  });

  return (
    <Paper withBorder p="xl" maw={420}>
      <form
        onSubmit={form.onSubmit(({ currentPassword, newPassword }) =>
          change.mutate(
            { currentPassword, newPassword },
            {
              onSuccess: () => {
                notifications.show({ message: tr.changePassword.done });
                navigate('/', { replace: true });
              },
            },
          ),
        )}
      >
        <Stack>
          <Title order={2}>{tr.changePassword.title}</Title>
          {me?.mustChangePassword && <Alert color="yellow">{tr.changePassword.mustChange}</Alert>}
          {change.error && <Alert color="red">{errorMessage(change.error)}</Alert>}
          <PasswordInput label={tr.changePassword.current} autoComplete="current-password"
            {...form.getInputProps('currentPassword')} />
          <PasswordInput label={tr.changePassword.next} autoComplete="new-password"
            {...form.getInputProps('newPassword')} />
          <PasswordInput label={tr.changePassword.confirm} autoComplete="new-password"
            {...form.getInputProps('confirm')} />
          <Button type="submit" loading={change.isPending}>
            {tr.changePassword.submit}
          </Button>
        </Stack>
      </form>
    </Paper>
  );
}
