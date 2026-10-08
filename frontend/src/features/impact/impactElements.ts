import type { ElementDefinition } from 'cytoscape';
import type { components } from '../../api/schema';

type ImpactResult = Pick<components['schemas']['ImpactResult'], 'nodes' | 'edges'>;

/** Graph elements for an impact result: one node per symbol, one edge per (from, to) pair. */
export function impactElements(result: ImpactResult): ElementDefinition[] {
  const nodes: ElementDefinition[] = (result.nodes ?? [])
    .filter((node) => node.symbolId != null)
    .map((node) => ({
      group: 'nodes',
      data: { id: String(node.symbolId), label: node.display ?? node.key ?? '', level: node.level ?? 0, role: node.role ?? '' },
    }));
  const ids = new Set(nodes.map((node) => node.data.id));
  const seen = new Set<string>();
  const edges: ElementDefinition[] = [];
  for (const edge of result.edges ?? []) {
    const from = String(edge.fromSymbolId);
    const to = String(edge.toSymbolId);
    const id = `${from}->${to}`;
    if (!ids.has(from) || !ids.has(to) || seen.has(id)) {
      continue;
    }
    seen.add(id);
    edges.push({ group: 'edges', data: { id, source: from, target: to } });
  }
  return [...nodes, ...edges];
}
