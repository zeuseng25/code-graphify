import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { tr } from '../i18n/tr';
import { signedIn } from '../test/backend';
import { renderApp } from '../test/renderApp';

describe('app header', () => {
  it('on a phone the header keeps only the theme and sign-out; the user and password change move out of it', async () => {
    const { user } = signedIn({ user: { source: 'LOCAL' } });
    renderApp('/');

    const name = await screen.findByText(user.displayName!);
    // Mantine hides these below the "sm" breakpoint with CSS; the menu drawer carries the password change instead
    expect(name).toHaveClass('mantine-visible-from-sm');
    expect(screen.getByText(tr.header.roles.USER).closest('.mantine-Badge-root')).toHaveClass('mantine-visible-from-sm');
    const passwordLinks = screen.getAllByRole('link', { name: tr.header.changePassword });
    expect(passwordLinks).toHaveLength(2);
    expect(passwordLinks.filter((link) => link.classList.contains('mantine-visible-from-sm'))).toHaveLength(1);
    expect(passwordLinks.filter((link) => link.classList.contains('mantine-hidden-from-sm'))).toHaveLength(1);
    expect(screen.getByRole('button', { name: new RegExp(`^${tr.header.theme.label}:`) })).not.toHaveClass('mantine-visible-from-sm');
  });
});
