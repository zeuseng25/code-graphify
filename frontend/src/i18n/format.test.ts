import { expect, it } from 'vitest';
import { tr } from './tr';
import { enumLabel } from './enumLabel';
import { formatDateTime, formatModulePath, formatNumber, formatRepositoryModule, formatSeconds } from './format';

it('labels enum values in Turkish and falls back safely', () => {
  expect(enumLabel(tr.enums.confidence, 'EXACT')).toBe(tr.enums.confidence.EXACT);
  expect(enumLabel(tr.enums.confidence, 'SOMETHING_NEW')).toBe('SOMETHING_NEW');
  expect(enumLabel(tr.enums.confidence, 'constructor')).toBe('constructor');
  expect(enumLabel(tr.enums.confidence, 'toString')).toBe('toString');
  expect(enumLabel(tr.enums.confidence, undefined)).toBe(tr.common.none);
});

it('formats dates and numbers for Turkish readers', () => {
  expect(formatNumber(12345)).toBe('12.345');
  expect(formatNumber(undefined)).toBe(tr.common.none);
  expect(formatDateTime('2026-10-07T09:05:00Z')).toMatch(/2026/);
  expect(formatDateTime(undefined)).toBe(tr.common.none);
});

it('shows seconds with at most one fraction digit', () => {
  expect(formatSeconds(1.2345)).toBe('1,2');
  expect(formatSeconds(2)).toBe('2');
  expect(formatSeconds(undefined)).toBe(tr.common.none);
});

it('names the repository root module instead of showing its "." path', () => {
  expect(formatModulePath('.')).toBe(tr.common.rootModule);
  expect(formatModulePath('zeus-base')).toBe('zeus-base');
  expect(formatModulePath(undefined)).toBe(tr.common.none);
  const repo = { projectKey: 'zeuseng25', slug: 'zeus-sample-bff' };
  expect(formatRepositoryModule(repo, '.')).toBe('zeuseng25/zeus-sample-bff');
  expect(formatRepositoryModule(repo, undefined)).toBe('zeuseng25/zeus-sample-bff');
  expect(formatRepositoryModule(repo, 'zeus-base')).toBe('zeuseng25/zeus-sample-bff / zeus-base');
});
