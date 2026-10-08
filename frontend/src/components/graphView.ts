/** Presentational: a layout fit never zooms past this, so a graph of a few nodes is not blown up to fill the canvas. */
export const MAX_FIT_ZOOM = 1.25;

/** Presentational: a node label wraps at this width (px). */
const LABEL_MAX_WIDTH = 160;

/** Node label placement shared by the graphs: under the node, wrapped instead of spreading across the canvas. */
export const NODE_LABEL_STYLE = {
  'text-valign': 'bottom', 'text-margin-y': 4, 'text-wrap': 'wrap', 'text-max-width': `${LABEL_MAX_WIDTH}px`,
  'text-overflow-wrap': 'anywhere',
} as const;

/** The part of a cytoscape graph the cap needs. */
interface Zoomable {
  zoom(): number;
  zoom(level: number): unknown;
  center(): unknown;
}

/** Undoes an over-eager layout fit: a laid-out graph zoomed past MAX_FIT_ZOOM is brought back to it and centred. */
export function capFitZoom(view: Zoomable): void {
  if (view.zoom() > MAX_FIT_ZOOM) {
    view.zoom(MAX_FIT_ZOOM);
    view.center();
  }
}
