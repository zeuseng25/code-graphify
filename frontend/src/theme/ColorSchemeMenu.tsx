import { Button, Menu, Text, useMantineColorScheme, VisuallyHidden, type MantineColorScheme } from '@mantine/core';
import { tr } from '../i18n/tr';

const SCHEMES: MantineColorScheme[] = ['light', 'dark', 'auto'];

/** Light, dark or the system's scheme; Mantine remembers the choice in this browser. */
export function ColorSchemeMenu() {
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const t = tr.header.theme;
  return (
    <Menu position="bottom-end">
      <Menu.Target>
        {/* a phone shows only the scheme; the label stays in the accessible name */}
        <Button variant="subtle" aria-label={`${t.label}: ${t[colorScheme]}`}>
          <Text span inherit visibleFrom="sm">{t.label}:&nbsp;</Text>
          {t[colorScheme]}
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        {SCHEMES.map((scheme) => (
          // Mantine fixes the role to menuitem, so the scheme in use is marked by a tick and a screen-reader hint
          <Menu.Item key={scheme} onClick={() => setColorScheme(scheme)} fw={scheme === colorScheme ? 700 : undefined}
            rightSection={scheme === colorScheme ? <span aria-hidden>{t.current}</span> : null}>
            {t[scheme]}
            {scheme === colorScheme && <VisuallyHidden> ({t.selected})</VisuallyHidden>}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}
