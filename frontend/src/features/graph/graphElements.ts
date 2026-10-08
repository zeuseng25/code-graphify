import type { ElementDefinition } from 'cytoscape';
import type { components } from '../../api/schema';
import { enumLabel } from '../../i18n/enumLabel';
import { formatModulePath } from '../../i18n/format';
import { tr } from '../../i18n/tr';

type RepoGraph = Pick<components['schemas']['RepoGraph'], 'nodes' | 'edges'>;
type GraphEdge = components['schemas']['GraphEdge'];
type GraphNode = components['schemas']['GraphNode'];

/** Presentational: distinguishable community colours, cycled when there are more communities. */
const COMMUNITY_PALETTE = ['#4c6ef5', '#12b886', '#fab005', '#e64980', '#7950f2', '#15aabf', '#fd7e14', '#82c91e',
  '#be4bdb', '#228be6'];
/** Presentational: nodes without a community (modules, packages, members, external nodes). */
const TYPE_COLOR: Record<string, string> = {
  MODULE: '#495057', PACKAGE: '#5c7cfa', METHOD: '#20c997', CONSTRUCTOR: '#20c997', FIELD: '#94d82d',
  EXTERNAL_REPOSITORY: '#adb5bd', EXTERNAL_LIBRARY: '#ced4da',
};
/** Presentational node sizing in px: a base plus a logarithmic share of what the node stands for. */
const BASE_SIZE = 16;
const SIZE_STEP = 6;

function nodeSize(weight: number | undefined): number {
  return BASE_SIZE + SIZE_STEP * Math.log2(1 + Math.max(0, weight ?? 0));
}

/** A node's shown name: a module node is labelled with its path, so the repository root module is named. */
export function nodeLabel(node: Pick<GraphNode, 'id' | 'label' | 'type'>): string {
  return node.type === 'MODULE' ? formatModulePath(node.label ?? node.id) : node.label ?? node.id ?? '';
}

export function graphElements(graph: RepoGraph): ElementDefinition[] {
  const nodes: ElementDefinition[] = (graph.nodes ?? []).filter((node, index, all) => node.id && all.findIndex((other) => other.id === node.id) === index).map((node) => {
    const metrics = node.metrics;
    const community = metrics?.communityId;
    return {
      group: 'nodes',
      data: {
        id: node.id!,
        label: nodeLabel(node),
        type: node.type ?? '',
        size: nodeSize(metrics ? metrics.dependents : node.size),
        color: community != null
          ? COMMUNITY_PALETTE[(community - 1 + COMMUNITY_PALETTE.length) % COMMUNITY_PALETTE.length]
          : TYPE_COLOR[node.type ?? ''] ?? TYPE_COLOR.PACKAGE,
        entry: metrics?.entryPoint === true,
      },
    };
  });
  const ids = new Set(nodes.map((node) => node.data.id));
  const edges: ElementDefinition[] = [];
  const seen = new Set<string>();
  for (const edge of graph.edges ?? []) {
    if (!edge.from || !edge.to || !ids.has(edge.from) || !ids.has(edge.to)) {
      continue;
    }
    const id = `${edge.from}->${edge.to}`;
    if (seen.has(id)) {
      continue;
    }
    seen.add(id);
    edges.push({
      group: 'edges',
      data: { id, source: edge.from, target: edge.to, width: 1 + Math.log2(Math.max(1, edge.weight ?? 1)) },
    });
  }
  return [...nodes, ...edges];
}

/** "Çağrı 3, Tip referansı 1": an edge's usage kinds in Turkish, largest first. */
export function edgeKinds(edge: Pick<GraphEdge, 'kinds'>): string {
  const entries = Object.entries(edge.kinds ?? {}).sort((a, b) => b[1] - a[1]);
  return entries.length
    ? entries.map(([kind, count]) => `${enumLabel(tr.enums.usageKind, kind)} ${count}`).join(', ')
    : tr.common.none;
}
