import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

const settings = [
  { key: 'graph.max_nodes', value: '500', type: 'INT', description: 'Repo grafında azami düğüm', minValue: 10, maxValue: 5000,
    updatedBy: 'admin', updatedAt: '2026-10-07T09:00:00Z' },
  { key: 'index.cron', value: '0 0 2 * * *', type: 'CRON', description: 'Tarama zamanı' },
];

describe('settings', () => {
  it('shows a rejected value under its field and keeps it', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/settings'), () => HttpResponse.json(settings)),
      http.put(apiUrl('/api/v1/admin/settings/index.cron'), () =>
        HttpResponse.json({ status: 400, detail: 'index.cron: not a cron expression with 6 fields' }, { status: 400 })),
    );
    renderApp('/admin/settings');

    const row = (await screen.findByText('index.cron')).closest('tr')!;
    const input = within(row).getByRole('textbox');
    await userEvent.clear(input);
    await userEvent.type(input, 'nope');
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.common.save }));

    expect(await within(row).findByText('index.cron: not a cron expression with 6 fields')).toBeInTheDocument();
    expect(input).toHaveValue('nope');
  });

  it('shows why the server cannot use a directory and keeps the typed path', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    const detail = 'Invalid value for index.workspace_dir: directory is not usable: /data/impact-analyzer/repos (Read-only file system)';
    server.use(
      http.get(apiUrl('/api/v1/admin/settings'), () => HttpResponse.json([
        { key: 'index.workspace_dir', value: '/tmp/graphify/repos', type: 'STRING', description: 'Repoların klonlandığı çalışma dizini' },
      ])),
      http.put(apiUrl('/api/v1/admin/settings/index.workspace_dir'), () =>
        HttpResponse.json({ status: 400, detail }, { status: 400 })),
    );
    renderApp('/admin/settings');

    const row = (await screen.findByText('index.workspace_dir')).closest('tr')!;
    const input = within(row).getByRole('textbox');
    await userEvent.clear(input);
    await userEvent.type(input, '/data/impact-analyzer/repos');
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.common.save }));

    expect(await within(row).findByText(detail)).toBeInTheDocument();
    expect(input).toHaveValue('/data/impact-analyzer/repos');
  });

  it('a saved value refreshes the UI settings', async () => {
    let uiConfigCalls = 0;
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/ui-config'), () => {
        uiConfigCalls++;
        return HttpResponse.json({ pageDefaultSize: 50, pageMaxSize: 500, graphMaxNodes: 800, impactDefaultDepth: 3,
          impactMaxDepth: 10, pollIntervalMillis: 20 });
      }),
      http.get(apiUrl('/api/v1/admin/settings'), () => HttpResponse.json(settings)),
      http.put(apiUrl('/api/v1/admin/settings/graph.max_nodes'), () =>
        HttpResponse.json({ ...settings[0], value: '800' })),
    );
    renderApp('/admin/settings');

    const row = (await screen.findByText('graph.max_nodes')).closest('tr')!;
    expect(within(row).getByText(tr.admin.settings.range('10', '5000'))).toBeInTheDocument();
    const input = within(row).getByRole('textbox');
    await userEvent.clear(input);
    await userEvent.type(input, '800');
    const before = uiConfigCalls;
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.common.save }));

    await waitFor(() => expect(uiConfigCalls).toBeGreaterThan(before));
    expect(await screen.findByText(tr.admin.common.saved)).toBeInTheDocument();
  });

  it('clears the previous refusal when the value is edited again', async () => {
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/settings'), () => HttpResponse.json(settings)),
      http.put(apiUrl('/api/v1/admin/settings/index.cron'), () =>
        HttpResponse.json({ status: 400, detail: 'bad cron' }, { status: 400 })),
    );
    renderApp('/admin/settings');

    const row = (await screen.findByText('index.cron')).closest('tr')!;
    const input = within(row).getByRole('textbox');
    await userEvent.clear(input);
    await userEvent.type(input, 'nope');
    await userEvent.click(within(row).getByRole('button', { name: tr.admin.common.save }));
    expect(await within(row).findByText('bad cron')).toBeInTheDocument();

    await userEvent.type(input, 'x');
    expect(within(row).queryByText('bad cron')).not.toBeInTheDocument();
    expect(within(row).getAllByText(tr.common.none).length).toBeGreaterThan(0);
  });
});
