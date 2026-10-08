import { Alert, Button, Center, Paper, PasswordInput, Stack, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { errorMessage } from '../api/errors';
import { safeNext } from '../auth/safeNext';
import { useLogin, useMe } from '../auth/session';
import { tr } from '../i18n/tr';

export function LoginPage() {
  const me = useMe();
  const login = useLogin();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const form = useForm({
    initialValues: { username: '', password: '' },
    validate: {
      username: (value) => (value.trim() ? null : tr.errors.required),
      password: (value) => (value ? null : tr.errors.required),
    },
  });

  if (me.data) {
    return <Navigate to={next} replace />;
  }
  return (
    <Center h="100vh">
      <Paper withBorder p="xl" w={360}>
        <form onSubmit={form.onSubmit((values) => login.mutate(values, { onSuccess: () => navigate(next, { replace: true }) }))}>
          <Stack>
            <Title order={2}>{tr.login.title}</Title>
            {login.error && <Alert color="red">{errorMessage(login.error)}</Alert>}
            <TextInput label={tr.login.username} autoComplete="username" {...form.getInputProps('username')} />
            <PasswordInput label={tr.login.password} autoComplete="current-password" {...form.getInputProps('password')} />
            <Button type="submit" loading={login.isPending}>
              {tr.login.submit}
            </Button>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}
