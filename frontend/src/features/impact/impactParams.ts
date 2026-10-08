import type { components } from '../../api/schema';
import type { UiConfig } from '../../config/UiConfigContext';
import type { UrlValue } from '../../hooks/useUrlState';

export type ImpactRequest = components['schemas']['ImpactRequest'];
type ChangeType = NonNullable<ImpactRequest['changeType']>;
type Confidence = NonNullable<ImpactRequest['confidences']>[number];

const CHANGE_TYPES: readonly ChangeType[] = ['SIGNATURE', 'BEHAVIOR'];
const CONFIDENCES: readonly Confidence[] = ['EXACT', 'RECOVERED', 'NAME_ONLY'];
/** The backend's own default when no change type is sent (impact spec §5.3). */
const DEFAULT_CHANGE_TYPE: ChangeType = 'BEHAVIOR';

/** A depth within 1..max. */
export function clampDepth(depth: number, max: number): number {
  return Math.max(1, Math.min(depth, max));
}

/** The analysis a URL describes, or null when it names no valid symbol. Limits come from /ui-config. */
export function impactRequestFrom(params: URLSearchParams, config: Pick<UiConfig, 'impactDefaultDepth' | 'impactMaxDepth'>): ImpactRequest | null {
  const symbolIds = [...new Set(params.getAll('symbol').map(Number).filter((id) => Number.isInteger(id) && id > 0))];
  if (symbolIds.length === 0) {
    return null;
  }
  const changeType = CHANGE_TYPES.find((value) => value === params.get('changeType')) ?? DEFAULT_CHANGE_TYPE;
  const rawDepth = Number(params.get('depth'));
  const depth = params.get('depth') !== null && Number.isInteger(rawDepth)
    ? clampDepth(rawDepth, config.impactMaxDepth)
    : config.impactDefaultDepth;
  const confidences = params.getAll('confidence').filter((value): value is Confidence =>
    (CONFIDENCES as readonly string[]).includes(value));
  return {
    symbolIds,
    changeType,
    depth,
    confidences: confidences.length ? confidences : undefined,
    includeDispatch: params.get('dispatch') !== 'false',
  };
}

/** URL changes that describe a request (the inverse of impactRequestFrom). */
export function impactParamsFrom(request: ImpactRequest): Record<string, UrlValue> {
  return {
    symbol: (request.symbolIds ?? []).map(String),
    changeType: request.changeType,
    depth: request.depth,
    confidence: request.confidences ?? null,
    dispatch: request.includeDispatch === false ? 'false' : null,
  };
}
