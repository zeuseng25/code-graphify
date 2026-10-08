import { useComputedColorScheme } from '@mantine/core';
import cytoscape from 'cytoscape';
import { useEffect, useRef } from 'react';
import { useNavigate } from 'react-router';
import type { components } from '../../api/schema';
import { capFitZoom, NODE_LABEL_STYLE } from '../../components/graphView';
import { impactElements } from './impactElements';

type ImpactResult = components['schemas']['ImpactResult'];

/** Presentational graph styling: seeds stand out, callers are drawn level by level from the seeds. */
function graphStyle(dark: boolean): cytoscape.StylesheetJson {
  return [
  { selector: 'node', style: {
    label: 'data(label)', 'font-size': 10, 'background-color': '#4c6ef5', width: 18, height: 18,
    color: dark ? '#f1f3f5' : '#212529', 'text-outline-width': 2, 'text-outline-color': dark ? '#1a1b1e' : '#ffffff',
    ...NODE_LABEL_STYLE,
  } },
  { selector: 'node[role = "SEED"]', style: { 'background-color': '#e03131', width: 26, height: 26 } },
  { selector: 'node[role = "DISPATCH"]', style: { 'background-color': '#f08c00' } },
  { selector: 'edge', style: { width: 1, 'line-color': '#adb5bd', 'target-arrow-shape': 'triangle', 'target-arrow-color': '#adb5bd', 'curve-style': 'bezier' } },
  ];
}

export default function ImpactGraphView({ result }: { result: ImpactResult }) {
  const container = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const dark = useComputedColorScheme('light') === 'dark';

  useEffect(() => {
    if (!container.current) {
      return;
    }
    const seeds = (result.nodes ?? [])
      .filter((node) => node.role === 'SEED' && node.symbolId != null)
      .map((node) => String(node.symbolId));
    const graph = cytoscape({
      container: container.current,
      elements: impactElements(result),
      style: graphStyle(dark),
      layout: { name: 'breadthfirst', directed: false, roots: seeds, spacingFactor: 1.2 },
    });
    capFitZoom(graph);
    graph.on('tap', 'node', (event) => navigate(`/symbols/${event.target.id()}`));
    return () => graph.destroy();
  }, [result, navigate, dark]);

  return <div ref={container} data-testid="impact-graph" style={{ height: 420, width: '100%' }} />;
}
