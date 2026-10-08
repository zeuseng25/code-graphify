import { act, render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { expect, it } from 'vitest';
import { useUrlState } from './useUrlState';

function setup(path: string) {
  let state: ReturnType<typeof useUrlState> | undefined;
  function Probe() {
    state = useUrlState();
    return null;
  }
  const router = createMemoryRouter([{ path: '*', element: <Probe /> }], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return { router, state: () => state! };
}

it('reads the page and resets it when a filter changes', () => {
  const { router, state } = setup('/x?q=foo&page=3&kind=METHOD');
  expect(state().page).toBe(3);

  act(() => state().update({ kind: 'CLASS' }));
  expect(router.state.location.search).toBe('?q=foo&kind=CLASS');

  act(() => state().update({ page: 2 }));
  expect(router.state.location.search).toBe('?q=foo&kind=CLASS&page=2');

  act(() => state().update({ confidence: ['EXACT', 'RECOVERED'], q: null }, true));
  expect(router.state.location.search).toBe('?kind=CLASS&page=2&confidence=EXACT&confidence=RECOVERED');
});

it('treats a bad page parameter as the first page', () => {
  expect(setup('/x?page=-4').state().page).toBe(0);
  expect(setup('/x?page=abc').state().page).toBe(0);
});

it('applies two updates made in the same tick', () => {
  const { router, state } = setup('/x');
  act(() => {
    state().update({ a: '1' });
    state().update({ b: '2', c: ['', 'x'] });
  });
  expect(router.state.location.search).toBe('?a=1&b=2&c=x');
});
