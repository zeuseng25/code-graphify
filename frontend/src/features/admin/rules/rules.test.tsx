import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { tr } from '../../../i18n/tr';
import { signedIn } from '../../../test/backend';
import { renderApp } from '../../../test/renderApp';
import { apiUrl, server } from '../../../test/server';

describe('entry point annotations and impact rules', () => {
  it('adds an annotation and disables another', async () => {
    const posts: unknown[] = [];
    const puts: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/entry-point-annotations'), () => HttpResponse.json([
        { id: 1, annotationFqn: 'org.springframework.web.bind.annotation.GetMapping', label: 'HTTP GET', enabled: true },
      ])),
      http.post(apiUrl('/api/v1/admin/entry-point-annotations'), async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ id: 2, annotationFqn: 'com.corp.Job', label: 'Batch', enabled: true }, { status: 201 });
      }),
      http.put(apiUrl('/api/v1/admin/entry-point-annotations/1'), async ({ request }) => {
        puts.push(await request.json());
        return HttpResponse.json({ id: 1, annotationFqn: 'org.springframework.web.bind.annotation.GetMapping', label: 'HTTP GET', enabled: false });
      }),
    );
    renderApp('/admin/entry-points');

    const row = (await screen.findByText('org.springframework.web.bind.annotation.GetMapping')).closest('tr')!;
    await userEvent.click(within(row).getByRole('switch'));
    await waitFor(() => expect(puts).toEqual([{ annotationFqn: 'org.springframework.web.bind.annotation.GetMapping', label: 'HTTP GET', enabled: false }]));

    await userEvent.type(screen.getByLabelText(tr.admin.entryPoints.fields.annotationFqn), 'com.corp.Job');
    await userEvent.type(screen.getByLabelText(tr.admin.entryPoints.fields.label), 'Batch');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.create }));
    await waitFor(() => expect(posts).toEqual([{ annotationFqn: 'com.corp.Job', label: 'Batch', enabled: true }]));
  });

  it('switches whether a usage kind propagates', async () => {
    const puts: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/impact-rules'), () => HttpResponse.json([{ kind: 'CALL', propagates: true, shownAtLevel1: true }])),
      http.put(apiUrl('/api/v1/admin/impact-rules/CALL'), async ({ request }) => {
        puts.push(await request.json());
        return HttpResponse.json({ kind: 'CALL', propagates: false, shownAtLevel1: true });
      }),
    );
    renderApp('/admin/impact-rules');

    const row = (await screen.findByText(tr.enums.usageKind.CALL)).closest('tr')!;
    await userEvent.click(within(row).getByRole('switch', { name: tr.admin.impactRules.columns.propagates }));

    await waitFor(() => expect(puts).toEqual([{ propagates: false, shownAtLevel1: true }]));
  });

  it('keeps the row locked until the refetch lands, so the second switch carries the new propagates value', async () => {
    const puts: Array<{ propagates: boolean; shownAtLevel1: boolean }> = [];
    let rule = { kind: 'CALL', propagates: true, shownAtLevel1: true };
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/impact-rules'), async () => {
        await new Promise((resolve) => setTimeout(resolve, 60));
        return HttpResponse.json([rule]);
      }),
      http.put(apiUrl('/api/v1/admin/impact-rules/CALL'), async ({ request }) => {
        const body = await request.json() as { propagates: boolean; shownAtLevel1: boolean };
        puts.push(body);
        rule = { kind: 'CALL', ...body };
        return HttpResponse.json(rule);
      }),
    );
    renderApp('/admin/impact-rules');

    const row = (await screen.findByText(tr.enums.usageKind.CALL)).closest('tr')!;
    await userEvent.click(within(row).getByRole('switch', { name: tr.admin.impactRules.columns.propagates }));
    // the second switch is not usable while the refetch is outstanding
    expect(within(row).getByRole('switch', { name: tr.admin.impactRules.columns.shownAtLevel1 })).toBeDisabled();
    await waitFor(() => expect(within(row).getByRole('switch', { name: tr.admin.impactRules.columns.shownAtLevel1 })).toBeEnabled());
    await userEvent.click(within(row).getByRole('switch', { name: tr.admin.impactRules.columns.shownAtLevel1 }));

    await waitFor(() => expect(puts).toEqual([
      { propagates: false, shownAtLevel1: true },
      { propagates: false, shownAtLevel1: false },
    ]));
  });

  it('shows a refused annotation in the form and omits a blank label', async () => {
    const posts: unknown[] = [];
    signedIn({ user: { role: 'ADMIN' } });
    server.use(
      http.get(apiUrl('/api/v1/admin/entry-point-annotations'), () => HttpResponse.json([])),
      http.post(apiUrl('/api/v1/admin/entry-point-annotations'), async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ status: 409, detail: 'Annotation already exists' }, { status: 409 });
      }),
    );
    renderApp('/admin/entry-points');

    await userEvent.type(await screen.findByLabelText(tr.admin.entryPoints.fields.annotationFqn), 'com.corp.Job');
    await userEvent.click(screen.getByRole('button', { name: tr.admin.common.create }));

    await waitFor(() => expect(posts).toEqual([{ annotationFqn: 'com.corp.Job', enabled: true }]));
    expect(await screen.findAllByText('Annotation already exists')).not.toHaveLength(0);
    expect(document.querySelector('.mantine-Alert-root')).toHaveTextContent('Annotation already exists');
  });
});
