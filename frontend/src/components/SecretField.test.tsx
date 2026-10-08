import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { KEEP_SECRET } from './secret';
import { SecretField } from './SecretField';

it('treats whitespace-only input as keep, real text as set, and labels the input', async () => {
  const onChange = vi.fn();
  render(<MantineProvider><SecretField label="Token" stored state={KEEP_SECRET} onChange={onChange} /></MantineProvider>);
  const input = screen.getByLabelText('Token');
  await userEvent.type(input, '  ');
  expect(onChange).toHaveBeenLastCalledWith({ mode: 'keep' });
  await userEvent.type(input, 'a');
  expect(onChange).toHaveBeenLastCalledWith({ mode: 'set', value: 'a' });
});
