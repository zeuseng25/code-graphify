import { Anchor } from '@mantine/core';
import { Link } from 'react-router';
import type { components } from '../api/schema';
import { tr } from '../i18n/tr';
import { Fqn } from './Fqn';

type SymbolRefLike = Pick<components['schemas']['SymbolRef'], 'id' | 'key' | 'display'>;

/**
 * A symbol by its display signature, linking to its detail page; the full key is the tooltip. The signature may wrap
 * at its dots and words, so a long one never widens its table or list.
 */
export function SymbolLink({ symbol }: { symbol?: SymbolRefLike }) {
  if (symbol?.id == null) {
    return <>{symbol?.display ?? symbol?.key ?? tr.common.none}</>;
  }
  return (
    <Anchor component={Link} to={`/symbols/${symbol.id}`} title={symbol.key}>
      <Fqn value={symbol.display ?? symbol.key ?? ''} />
    </Anchor>
  );
}
