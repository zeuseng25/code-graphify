import { Stack, Text, useComputedColorScheme } from '@mantine/core';
import cytoscape from 'cytoscape';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import type { components } from '../../api/schema';
import { capFitZoom, NODE_LABEL_STYLE } from '../../components/graphView';
import { tr } from '../../i18n/tr';
import { edgeKinds, graphElements, nodeLabel } from './graphElements';

type RepoGraph = components['schemas']['RepoGraph'];
type GraphEdge = components['schemas']['GraphEdge'];

/** Presentational styling: shape by node type, colour and size from the element data, readable in both schemes. */
function graphStyle(dark: boolean): cytoscape.StylesheetJson {
  return [
    { selector: 'node', style: {
      label: 'data(label)', 'font-size': 10, 'background-color': 'data(color)', width: 'data(size)', height: 'data(size)',
      color: dark ? '#f1f3f5' : '#212529', 'text-outline-width': 2, 'text-outline-color': dark ? '#1a1b1e' : '#ffffff',
      ...NODE_LABEL_STYLE,
    } },
    // the module grey is too dark to see on the dark background
    { selector: 'node[type = "MODULE"]', style: { shape: 'round-rectangle', ...(dark ? { 'background-color': '#adb5bd' } : {}) } },
    { selector: 'node[type = "PACKAGE"]', style: { shape: 'barrel' } },
    { selector: 'node[type = "METHOD"], node[type = "CONSTRUCTOR"]', style: { shape: 'round-diamond' } },
    { selector: 'node[type = "FIELD"]', style: { shape: 'round-triangle' } },
    { selector: 'node[type = "EXTERNAL_REPOSITORY"], node[type = "EXTERNAL_LIBRARY"]', style: { shape: 'hexagon' } },
    { selector: 'node[?entry]', style: { 'border-width': 3, 'border-color': '#e03131' } },
    { selector: 'edge', style: {
      width: 'data(width)', 'line-color': '#adb5bd', 'target-arrow-shape': 'triangle', 'target-arrow-color': '#adb5bd',
      'curve-style': 'bezier',
    } },
  ];
}

/** The repository graph (cose layout); a node tap is handed to the page, an edge tap shows its usage kinds. */
export default function RepoGraphView({ graph, onNode }: { graph: RepoGraph; onNode: (id: string) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const dark = useComputedColorScheme('light') === 'dark';
  const [edge, setEdge] = useState<GraphEdge | null>(null);
  const instance = useRef<cytoscape.Core | null>(null);
  const tapNode = useEffectEvent((id: string) => onNode(id));
  // the scheme the next build uses; kept current by the style effect, so a rebuild after a toggle is not stale
  const currentDark = useRef(dark);

  useEffect(() => {
    if (!container.current) {
      return;
    }
    setEdge(null);
    const edges = new Map((graph.edges ?? []).map((e) => [`${e.from}->${e.to}`, e]));
    const view = cytoscape({
      container: container.current,
      elements: graphElements(graph),
      style: graphStyle(currentDark.current),
      layout: { name: 'cose', animate: false },
    });
    capFitZoom(view);
    view.on('tap', 'node', (event) => tapNode(event.target.id()));
    view.on('tap', 'edge', (event) => setEdge(edges.get(event.target.id()) ?? null));
    instance.current = view;
    return () => {
      instance.current = null;
      view.destroy();
    };
  }, [graph]);

  useEffect(() => {
    currentDark.current = dark;
    instance.current?.style(graphStyle(dark));
  }, [dark]);

  const labelOf = (id: string | undefined) => {
    const node = graph.nodes?.find((candidate) => candidate.id === id);
    return node ? nodeLabel(node) : id ?? tr.common.none;
  };
  return (
    <Stack gap="xs">
      <div ref={container} data-testid="repo-graph" style={{ height: 560, width: '100%' }} />
      <Text size="sm" c="dimmed">
        {edge ? tr.graph.edgeInfo(labelOf(edge.from), labelOf(edge.to), edgeKinds(edge)) : tr.graph.tapHint}
      </Text>
    </Stack>
  );
}
