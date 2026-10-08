import { tr } from '../i18n/tr';
import type { Role } from '../auth/session';

export interface NavItem {
  path: string;
  label: string;
  /** Only users with this role see the item; the backend enforces the same rule. */
  role?: Role;
}

/** The menu; later plans add their screens here. */
export const NAV_ITEMS: NavItem[] = [
  { path: '/', label: tr.nav.search },
  { path: '/impact', label: tr.nav.impact },
  { path: '/repositories', label: tr.nav.repositories },
  { path: '/runs', label: tr.nav.runs },
  { path: '/admin/scm-connections', label: tr.admin.nav.scmConnections, role: 'ADMIN' },
  { path: '/admin/artifact-repositories', label: tr.admin.nav.artifactRepositories, role: 'ADMIN' },
  { path: '/admin/users', label: tr.admin.nav.users, role: 'ADMIN' },
  { path: '/admin/ldap', label: tr.admin.nav.ldap, role: 'ADMIN' },
  { path: '/admin/settings', label: tr.admin.nav.settings, role: 'ADMIN' },
  { path: '/admin/entry-points', label: tr.admin.nav.entryPoints, role: 'ADMIN' },
  { path: '/admin/impact-rules', label: tr.admin.nav.impactRules, role: 'ADMIN' },
  { path: '/admin/audit', label: tr.admin.nav.audit, role: 'ADMIN' },
];

export function visibleItems(items: NavItem[], role: Role | undefined): NavItem[] {
  return items.filter((item) => !item.role || item.role === role);
}

/** An item is active on its own path and below it; the root item also covers the symbol pages. */
export function isActive(itemPath: string, pathname: string): boolean {
  if (itemPath === '/') {
    return pathname === '/' || pathname === '/symbols' || pathname.startsWith('/symbols/');
  }
  return pathname === itemPath || pathname.startsWith(`${itemPath}/`);
}
