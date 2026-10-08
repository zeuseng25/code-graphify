import type { components } from '../../api/schema';
import type { UrlValue } from '../../hooks/useUrlState';
import { tr } from '../../i18n/tr';

export type GraphLevel = NonNullable<components['schemas']['RepoGraph']['level']>;
type GraphNode = components['schemas']['GraphNode'];

export const LEVELS: readonly GraphLevel[] = ['MODULE', 'PACKAGE', 'CLASS', 'METHOD'];

/** The backend's name and focus token for the unnamed package (RepoGraphAnalyzer.DEFAULT_PACKAGE). */
export const DEFAULT_PACKAGE_FOCUS = '(default package)';

/** Node id prefixes of the graph API (repograph/GraphBuilder). */
const MODULE_ID = 'module:';
const PACKAGE_ID = 'package:';
const CLASS_ID = 'class:';
const MEMBER_ID = 'member:';

export interface GraphQuery {
  level?: GraphLevel;
  focus?: string;
  includeExternal: boolean;
}

export type DrillTarget = { kind: 'graph'; level: GraphLevel; focus?: string } | { kind: 'search'; q: string };

export interface Crumb {
  label: string;
  level: GraphLevel;
  focus?: string;
}

/** The graph a URL describes; unknown levels and blank focuses are dropped, MODULE takes no focus. */
export function graphQueryFrom(params: URLSearchParams): GraphQuery {
  const level = LEVELS.find((value) => value === params.get('level'));
  const focus = params.get('focus')?.trim() || undefined;
  const query: GraphQuery = { includeExternal: params.get('includeExternal') === 'true' };
  if (level) {
    query.level = level;
  }
  if (focus && level !== 'MODULE') {
    query.focus = focus;
  }
  return query;
}

export function graphParamsFrom(query: GraphQuery): Record<string, UrlValue> {
  return { level: query.level, focus: query.focus, includeExternal: query.includeExternal ? 'true' : null };
}

export function graphHref(repositoryId: number, query: GraphQuery): string {
  const params = new URLSearchParams();
  if (query.level) params.set('level', query.level);
  if (query.focus) params.set('focus', query.focus);
  if (query.includeExternal) params.set('includeExternal', 'true');
  const search = params.toString();
  return `/repositories/${repositoryId}/graph${search ? `?${search}` : ''}`;
}

/** The package of a binary class name; the unnamed package has the backend's token. */
export function packageOf(classFqn: string): string {
  const dot = classFqn.lastIndexOf('.');
  return dot < 0 ? DEFAULT_PACKAGE_FOCUS : classFqn.slice(0, dot);
}

/** The level control: MODULE takes no focus; leaving METHOD focuses the class's package. */
export function levelChange(query: GraphQuery, target: GraphLevel): GraphQuery {
  if (target === 'MODULE') {
    return { level: 'MODULE', includeExternal: query.includeExternal };
  }
  const focus = query.level === 'METHOD' && query.focus ? packageOf(query.focus) : query.focus;
  return focus ? { level: target, focus, includeExternal: query.includeExternal }
    : { level: target, includeExternal: query.includeExternal };
}

/** Where a tap on a node leads; graph nodes carry no symbol id, so members open the symbol search. */
export function drillDown(node: Pick<GraphNode, 'id' | 'label' | 'type'>): DrillTarget | null {
  const id = node.id ?? '';
  if (id.startsWith(MODULE_ID)) {
    return { kind: 'graph', level: 'PACKAGE' };
  }
  if (id.startsWith(PACKAGE_ID)) {
    const name = id.slice(PACKAGE_ID.length) || node.label;
    return name ? { kind: 'graph', level: 'CLASS', focus: name } : null;
  }
  if (id.startsWith(CLASS_ID)) {
    const fqn = id.slice(CLASS_ID.length);
    return fqn ? { kind: 'graph', level: 'METHOD', focus: fqn } : null;
  }
  if (id.startsWith(MEMBER_ID)) {
    // Name-only keys end in "/arity"; field keys are "<class>.<name>" and search as "<class>#<name>".
    let q = id.slice(MEMBER_ID.length).split(/[(/]/)[0];
    // only fields: a CLASS member node (a field initializer) keeps its key and searches as the class itself
    if (node.type === 'FIELD' && !q.includes('#')) {
      const dot = q.lastIndexOf('.');
      q = dot > 0 ? `${q.slice(0, dot)}#${q.slice(dot + 1)}` : q;
    }
    return q ? { kind: 'search', q } : null;
  }
  return null;
}

/** The way back up from what is shown (the response's level and focus, which may be a rollup). */
export function breadcrumb(level: GraphLevel | undefined, focus: string | undefined): Crumb[] {
  const crumbs: Crumb[] = [{ label: tr.graph.crumbs.modules, level: 'MODULE' }];
  if (!level || level === 'MODULE') {
    return crumbs;
  }
  crumbs.push({ label: tr.graph.crumbs.packages, level: 'PACKAGE' });
  if (level === 'PACKAGE') {
    if (focus) crumbs.push({ label: focus, level: 'PACKAGE', focus });
    return crumbs;
  }
  if (level === 'CLASS') {
    crumbs.push(focus ? { label: focus, level: 'CLASS', focus } : { label: tr.graph.crumbs.classes, level: 'CLASS' });
    return crumbs;
  }
  if (focus) {
    const pkg = packageOf(focus);
    crumbs.push({ label: pkg, level: 'CLASS', focus: pkg });
    crumbs.push({ label: focus.slice(focus.lastIndexOf('.') + 1), level: 'METHOD', focus });
  }
  return crumbs;
}
