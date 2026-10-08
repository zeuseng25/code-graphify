import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { Pager } from './Pager';

it('is hidden for a single page and reports 0-based pages', async () => {
  const onChange = vi.fn();
  const { container, rerender } = render(<MantineProvider><Pager page={0} size={50} total={50} onChange={onChange} /></MantineProvider>);
  expect(container.querySelector('button')).toBeNull();

  rerender(<MantineProvider><Pager page={0} size={50} total={120} onChange={onChange} /></MantineProvider>);
  await userEvent.click(screen.getByRole('button', { name: '3' }));
  expect(onChange).toHaveBeenCalledWith(2);
});
