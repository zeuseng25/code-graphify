import { Badge, Button, Group, PasswordInput, Stack, Text } from '@mantine/core';
import { tr } from '../i18n/tr';
import type { SecretState } from './secret';

/** A token or password: never pre-filled; empty keeps the stored value, "Kayıtlı değeri sil" clears it. */
export function SecretField({ label, stored, state, onChange, reentry, description }: {
  label: string;
  stored: boolean;
  state: SecretState;
  onChange: (state: SecretState) => void;
  /** The target that changed, when the stored secret must be typed again. */
  reentry?: string;
  /** A hint shown before the stored badge. */
  description?: string;
}) {
  const t = tr.admin.secret;
  return (
    <Stack gap={4}>
      <PasswordInput
        label={label}
        autoComplete="new-password"
        disabled={stored && state.mode === 'clear'}
        value={state.mode === 'set' ? state.value : ''}
        onChange={(event) => {
          const value = event.currentTarget.value;
          onChange(value.trim() ? { mode: 'set', value } : { mode: 'keep' });
        }}
        error={reentry ? t.reenter(reentry) : undefined}
        description={(
          <>
            {description ? `${description} ` : ''}
            <Badge size="sm" variant="light" color={stored ? 'green' : 'gray'}>{stored ? t.stored : t.empty}</Badge>
            {stored && state.mode === 'keep' && !reentry ? ` ${t.keepHint}` : ''}
          </>
        )}
      />
      {stored && (
        <Group gap="xs">
          {state.mode === 'clear' ? (
            <>
              <Text size="sm" c="orange">{t.willClear}</Text>
              <Button size="xs" variant="subtle" onClick={() => onChange({ mode: 'keep' })}>{t.undoClear}</Button>
            </>
          ) : (
            <Button size="xs" variant="subtle" color="red" onClick={() => onChange({ mode: 'clear' })}>{t.clear}</Button>
          )}
        </Group>
      )}
    </Stack>
  );
}
