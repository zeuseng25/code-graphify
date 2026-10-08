import { render } from '@testing-library/react';
import { expect, it } from 'vitest';
import { Fqn } from './Fqn';

it('lets a qualified name break after its dots, and copies as the plain name', () => {
  const { container } = render(<p><Fqn value="com.mb.api.Orders" /></p>);

  expect(container.textContent).toBe('com.mb.api.Orders');
  expect(container.innerHTML).toBe('<p>com.<wbr>mb.<wbr>api.<wbr>Orders</p>');
});

it('lets a long class name also break between its words', () => {
  const { container } = render(<p><Fqn value="com.mb.BackendOldApplicationTests" /></p>);

  expect(container.textContent).toBe('com.mb.BackendOldApplicationTests');
  expect(container.innerHTML).toBe('<p>com.<wbr>mb.<wbr>Backend<wbr>Old<wbr>Application<wbr>Tests</p>');
});

it('keeps acronyms and lower-case package words whole', () => {
  const { container } = render(<p><Fqn value="com.mb.api.HTTPClient" /></p>);

  expect(container.innerHTML).toBe('<p>com.<wbr>mb.<wbr>api.<wbr>HTTPClient</p>');
});
