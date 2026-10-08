import { expect, it } from 'vitest';
import { isActive, NAV_ITEMS, visibleItems, type NavItem } from './navigation';

it('shows admin items to admins only', () => {
  const items: NavItem[] = [
    { path: '/', label: 'a' },
    { path: '/admin/x', label: 'b', role: 'ADMIN' },
  ];
  expect(visibleItems(items, 'USER').map((i) => i.path)).toEqual(['/']);
  expect(visibleItems(items, 'ADMIN').map((i) => i.path)).toEqual(['/', '/admin/x']);
});

it('marks a menu item active on its path and below, and the root only for search and symbols', () => {
  expect(isActive('/', '/')).toBe(true);
  expect(isActive('/', '/symbols/12')).toBe(true);
  expect(isActive('/', '/runs')).toBe(false);
  expect(isActive('/runs', '/runs/5')).toBe(true);
  expect(isActive('/runs', '/runs')).toBe(true);
  expect(isActive('/runs', '/runsX')).toBe(false);
  expect(isActive('/impact', '/')).toBe(false);
});

it('lists every admin page for admins only', () => {
  const adminPaths = NAV_ITEMS.filter((item) => item.role === 'ADMIN').map((item) => item.path);
  expect(adminPaths).toEqual(['/admin/scm-connections', '/admin/artifact-repositories', '/admin/users', '/admin/ldap',
    '/admin/settings', '/admin/entry-points', '/admin/impact-rules', '/admin/audit']);
  expect(visibleItems(NAV_ITEMS, 'USER').some((item) => item.role === 'ADMIN')).toBe(false);
});
