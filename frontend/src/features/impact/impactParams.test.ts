import { expect, it } from 'vitest';
import { testUiConfig } from '../../test/backend';
import { impactParamsFrom, impactRequestFrom } from './impactParams';

it('builds the request from the URL with UI-config defaults and limits', () => {
  expect(impactRequestFrom(new URLSearchParams('symbol=7&symbol=9'), testUiConfig)).toEqual({
    symbolIds: [7, 9], changeType: 'BEHAVIOR', depth: testUiConfig.impactDefaultDepth, confidences: undefined,
    includeDispatch: true,
  });
  expect(impactRequestFrom(new URLSearchParams('symbol=7&depth=99&changeType=SIGNATURE&confidence=EXACT&dispatch=false'),
    testUiConfig)).toEqual({
    symbolIds: [7], changeType: 'SIGNATURE', depth: testUiConfig.impactMaxDepth, confidences: ['EXACT'],
    includeDispatch: false,
  });
  expect(impactRequestFrom(new URLSearchParams('symbol=abc'), testUiConfig)).toBeNull();
  expect(impactRequestFrom(new URLSearchParams(''), testUiConfig)).toBeNull();
});

it('dedupes symbols and clamps depth to at least 1', () => {
  expect(impactRequestFrom(new URLSearchParams('symbol=9&symbol=7&symbol=9&depth=0'), testUiConfig))
    .toEqual(expect.objectContaining({ symbolIds: [9, 7], depth: 1 }));
  expect(impactRequestFrom(new URLSearchParams('symbol=9&depth=x'), testUiConfig))
    .toEqual(expect.objectContaining({ depth: testUiConfig.impactDefaultDepth }));
});

it('writes a request back to URL changes', () => {
  expect(impactParamsFrom({ symbolIds: [7], changeType: 'SIGNATURE', depth: 2, confidences: ['EXACT'], includeDispatch: false }))
    .toEqual({ symbol: ['7'], changeType: 'SIGNATURE', depth: 2, confidence: ['EXACT'], dispatch: 'false' });
});
