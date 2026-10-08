import { expect, it } from 'vitest';
import { impactElements } from './impactElements';

it('turns a result into graph nodes and de-duplicated edges', () => {
  const elements = impactElements({
    nodes: [
      { symbolId: 1, display: 'format(int)', level: 0, role: 'SEED' },
      { symbolId: 2, display: 'total()', level: 1, role: 'AFFECTED' },
    ],
    edges: [
      { fromSymbolId: 2, toSymbolId: 1, kind: 'CALL', level: 1 },
      { fromSymbolId: 2, toSymbolId: 1, kind: 'CALL', level: 1 },
    ],
  });

  expect(elements.filter((e) => e.group === 'nodes').map((e) => e.data.id)).toEqual(['1', '2']);
  expect(elements.filter((e) => e.group === 'edges')).toHaveLength(1);
  expect(elements.find((e) => e.data.id === '1')?.data.role).toBe('SEED');
});
