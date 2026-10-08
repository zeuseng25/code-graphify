import { describe, expect, it, vi } from 'vitest';
import { capFitZoom, MAX_FIT_ZOOM, NODE_LABEL_STYLE } from './graphView';

describe('graph view helpers', () => {
  it('a graph of a few nodes is not blown up to fill the canvas', () => {
    const view = { zoom: vi.fn(() => 6), center: vi.fn() };
    capFitZoom(view);
    expect(view.zoom).toHaveBeenCalledWith(MAX_FIT_ZOOM);
    expect(view.center).toHaveBeenCalled();
  });

  it('a large graph keeps the zoom its layout fitted', () => {
    const view = { zoom: vi.fn(() => 0.4), center: vi.fn() };
    capFitZoom(view);
    expect(view.zoom.mock.calls.every((call: unknown[]) => call.length === 0)).toBe(true);
    expect(view.center).not.toHaveBeenCalled();
  });

  it('labels sit under their node and wrap', () => {
    expect(NODE_LABEL_STYLE).toMatchObject({ 'text-valign': 'bottom', 'text-wrap': 'wrap', 'text-overflow-wrap': 'anywhere' });
  });
});
