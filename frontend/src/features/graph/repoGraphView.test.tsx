import { MantineProvider, useMantineColorScheme } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import cytoscape from 'cytoscape';
import { describe, expect, it, vi } from 'vitest';
import RepoGraphView from './RepoGraphView';

const view = vi.hoisted(() => ({ on: vi.fn(), style: vi.fn(), destroy: vi.fn(), zoom: vi.fn(() => 1), center: vi.fn() }));
vi.mock('cytoscape', () => ({ default: vi.fn(() => view) }));

function Toggle() {
  const { setColorScheme } = useMantineColorScheme();
  return <button onClick={() => setColorScheme('dark')}>dark</button>;
}

describe('RepoGraphView', () => {
  it('a colour scheme change restyles the graph without laying it out again', async () => {
    const graph = { repositoryId: 1, level: 'CLASS' as const, nodes: [{ id: 'a', label: 'a', type: 'CLASS' as const, size: 1 }], edges: [] };
    render(<MantineProvider defaultColorScheme="light"><Toggle /><RepoGraphView graph={graph} onNode={() => {}} /></MantineProvider>);
    expect(vi.mocked(cytoscape)).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: 'dark' }));

    expect(vi.mocked(cytoscape)).toHaveBeenCalledTimes(1);
    expect(view.destroy).not.toHaveBeenCalled();
    expect(view.style).toHaveBeenCalled();
  });

  it('a graph of a few nodes is not blown up to fill the canvas', () => {
    const graph = { repositoryId: 1, level: 'PACKAGE' as const, nodes: [{ id: 'p', label: 'p', type: 'PACKAGE' as const, size: 1 }], edges: [] };
    view.zoom.mockReset().mockReturnValue(6);
    view.center.mockReset();
    render(<MantineProvider><RepoGraphView graph={graph} onNode={() => {}} /></MantineProvider>);

    expect(view.zoom).toHaveBeenCalledWith(1.25);
    expect(view.center).toHaveBeenCalled();
  });

  it('a large graph keeps the zoom its layout fitted', () => {
    const graph = { repositoryId: 1, level: 'CLASS' as const, nodes: [{ id: 'c', label: 'c', type: 'CLASS' as const, size: 1 }], edges: [] };
    view.zoom.mockReset().mockReturnValue(0.4);
    view.center.mockReset();
    render(<MantineProvider><RepoGraphView graph={graph} onNode={() => {}} /></MantineProvider>);

    // only read, never set
    expect(view.zoom.mock.calls.every((call) => call.length === 0)).toBe(true);
    expect(view.center).not.toHaveBeenCalled();
  });

  it('labels sit under their node and wrap instead of spreading across the canvas', () => {
    const graph = { repositoryId: 1, level: 'PACKAGE' as const, nodes: [{ id: 'p', label: 'p', type: 'PACKAGE' as const, size: 1 }], edges: [] };
    vi.mocked(cytoscape).mockClear();
    render(<MantineProvider><RepoGraphView graph={graph} onNode={() => {}} /></MantineProvider>);

    const options = vi.mocked(cytoscape).mock.calls[0][0] as unknown as { style: { selector: string; style: Record<string, unknown> }[] };
    const style = options.style;
    expect(style.find((rule) => rule.selector === 'node')!.style).toMatchObject({
      'text-valign': 'bottom', 'text-wrap': 'wrap', 'text-overflow-wrap': 'anywhere',
    });
  });

  it('module nodes stay visible on the dark background', () => {
    const graph = { repositoryId: 1, level: 'MODULE' as const, nodes: [{ id: 'module:.', label: '.', type: 'MODULE' as const, size: 1 }], edges: [] };
    vi.mocked(cytoscape).mockClear();
    render(<MantineProvider forceColorScheme="dark"><RepoGraphView graph={graph} onNode={() => {}} /></MantineProvider>);

    const options = vi.mocked(cytoscape).mock.calls[0][0] as unknown as { style: { selector: string; style: Record<string, unknown> }[] };
    expect(options.style.find((rule) => rule.selector === 'node[type = "MODULE"]')!.style['background-color']).toBe('#adb5bd');
  });
});
