import { expect, it } from 'vitest';
import { tr } from '../../i18n/tr';
import { edgeKinds, graphElements, nodeLabel } from './graphElements';

const graph = {
  nodes: [
    { id: 'class:a.A', label: 'a.A', type: 'CLASS' as const, size: 1,
      metrics: { dependents: 0, communityId: 1, entryPoint: false } },
    { id: 'class:a.B', label: 'a.B', type: 'CLASS' as const, size: 1,
      metrics: { dependents: 15, communityId: 2, entryPoint: true } },
    { id: 'external:lib:x', label: 'x', type: 'EXTERNAL_LIBRARY' as const, size: 3 },
  ],
  edges: [{ from: 'class:a.A', to: 'class:a.B', weight: 4, kinds: { CALL: 3, TYPE_REF: 1 } }],
};

it('turns a repository graph into styled elements', () => {
  const elements = graphElements(graph);
  const node = (id: string) => elements.find((e) => e.data.id === id)!.data;

  expect(node('class:a.B').size).toBeGreaterThan(node('class:a.A').size);
  expect(node('class:a.A').color).not.toBe(node('class:a.B').color);
  expect(node('class:a.B').entry).toBe(true);
  expect(node('external:lib:x').type).toBe('EXTERNAL_LIBRARY');
  const edge = elements.find((e) => e.data.id === 'class:a.A->class:a.B')!.data;
  expect(edge).toMatchObject({ source: 'class:a.A', target: 'class:a.B' });
  expect(edge.width).toBeGreaterThan(1);
});

it('drops edges whose ends are not in the graph', () => {
  const elements = graphElements({ nodes: graph.nodes, edges: [{ from: 'class:a.A', to: 'class:gone', weight: 1 }] });
  expect(elements.filter((e) => e.data.source)).toHaveLength(0);
});

it('summarises an edge in Turkish, largest count first', () => {
  expect(edgeKinds(graph.edges[0])).toBe(`${tr.enums.usageKind.CALL} 3, ${tr.enums.usageKind.TYPE_REF} 1`);
  expect(edgeKinds({})).toBe(tr.common.none);
});

it('keeps the first of duplicate node and edge ids', () => {
  const dup = { id: 'class:a.A', label: 'again', type: 'CLASS' as const, size: 1 };
  const edge = { from: 'class:a.A', to: 'class:a.B', weight: 1 };
  const elements = graphElements({ nodes: [...graph.nodes, dup], edges: [edge, edge] });
  expect(elements.filter((e) => e.data.id === 'class:a.A')).toHaveLength(1);
  expect(elements.find((e) => e.data.id === 'class:a.A')!.data.label).toBe('a.A');
  expect(elements.filter((e) => e.data.source)).toHaveLength(1);
});

it('wraps community colours beyond the palette deterministically', () => {
  const at = (communityId: number) => graphElements({
    nodes: [{ id: 'class:x', type: 'CLASS' as const, metrics: { dependents: 0, communityId, entryPoint: false } }],
  })[0].data.color;
  expect(at(11)).toBe(at(1));
  expect(at(12)).toBe(at(2));
  expect(at(2)).not.toBe(at(1));
});

it('names the repository root module node instead of labelling it "."', () => {
  const elements = graphElements({ nodes: [
    { id: 'module:.', label: '.', type: 'MODULE' as const, size: 1 },
    { id: 'module:core', label: 'core', type: 'MODULE' as const, size: 1 },
    { id: 'package:a', label: '.', type: 'PACKAGE' as const, size: 1 },
  ], edges: [] });
  const label = (id: string) => elements.find((e) => e.data.id === id)!.data.label;

  expect(label('module:.')).toBe(tr.common.rootModule);
  expect(label('module:core')).toBe('core');
  expect(label('package:a')).toBe('.');
  expect(nodeLabel({ id: 'module:.', label: '.', type: 'MODULE' })).toBe(tr.common.rootModule);
});
