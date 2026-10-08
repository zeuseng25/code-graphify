import { AppShell, Badge, Burger, Button, Group, NavLink, Stack, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useEffect } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import { useLogout, useMe } from '../auth/session';
import { UiConfigProvider } from '../config/UiConfigContext';
import { tr } from '../i18n/tr';
import { ColorSchemeMenu } from '../theme/ColorSchemeMenu';
import { NAV_ITEMS, isActive, visibleItems } from './navigation';

/** The signed-in frame: header with the user, the role-aware menu and the page. */
export function AppLayout() {
  const me = useMe().data;
  const logout = useLogout();
  const navigate = useNavigate();
  const location = useLocation();
  const [opened, { toggle, close }] = useDisclosure(false);
  const items = visibleItems(NAV_ITEMS, me?.role);
  const userItems = items.filter((item) => !item.role);
  const adminItems = items.filter((item) => item.role === 'ADMIN');

  // the menu is a drawer on small screens: it closes once a page is chosen
  useEffect(close, [close, location.pathname]);

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label={tr.nav.menu} />
            <Title order={3}>{tr.app.name}</Title>
          </Group>
          <Group gap="xs" wrap="nowrap">
            {/* on a phone only the theme and sign-out fit; the password change is in the menu drawer there */}
            <Text visibleFrom="sm">{me?.displayName ?? me?.username}</Text>
            {me?.role && <Badge variant="light" visibleFrom="sm">{tr.header.roles[me.role]}</Badge>}
            {me?.source === 'LOCAL' && (
              <Button variant="subtle" component={Link} to="/change-password" visibleFrom="sm">
                {tr.header.changePassword}
              </Button>
            )}
            <ColorSchemeMenu />
            <Button variant="default" onClick={() => logout.mutate(undefined, { onSettled: () => navigate('/login') })}>
              {tr.header.logout}
            </Button>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        <Stack gap={0}>
          {userItems.map((item) => (
            <NavLink key={item.path} component={Link} to={item.path} label={item.label}
              active={isActive(item.path, location.pathname)} />
          ))}
          {me?.source === 'LOCAL' && (
            <NavLink hiddenFrom="sm" component={Link} to="/change-password" label={tr.header.changePassword}
              active={isActive('/change-password', location.pathname)} />
          )}
          {adminItems.length > 0 && (
            <NavLink label={tr.nav.admin} defaultOpened>
              {adminItems.map((item) => (
                <NavLink key={item.path} component={Link} to={item.path} label={item.label}
                  active={isActive(item.path, location.pathname)} />
              ))}
            </NavLink>
          )}
        </Stack>
      </AppShell.Navbar>
      <AppShell.Main>
        {me?.mustChangePassword ? (
          // The backend withholds the UI settings until the password is changed; that page needs none.
          <Outlet />
        ) : (
          <UiConfigProvider>
            <Outlet />
          </UiConfigProvider>
        )}
      </AppShell.Main>
    </AppShell>
  );
}
