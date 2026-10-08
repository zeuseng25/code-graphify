import { expect, it } from 'vitest';
import { safeNext } from './safeNext';

it('keeps in-app paths and drops anything that could leave the app', () => {
  expect(safeNext('/repositories/3?tab=runs')).toBe('/repositories/3?tab=runs');
  expect(safeNext(null)).toBe('/');
  expect(safeNext('//evil.example/x')).toBe('/');
  expect(safeNext('/\\evil.example')).toBe('/');
  expect(safeNext('https://evil.example')).toBe('/');
  expect(safeNext('relative')).toBe('/');
  expect(safeNext('/ok/path?x=1#h')).toBe('/ok/path?x=1#h');
});

it('rejects control characters and whitespace that browsers strip or turn into another host', () => {
  expect(safeNext('/\t/evil.example')).toBe('/');
  expect(safeNext('/\n/evil')).toBe('/');
  expect(safeNext(decodeURIComponent('/%09/evil'))).toBe('/');
  expect(safeNext(new URLSearchParams('next=/%09/evil').get('next'))).toBe('/');
  expect(safeNext(' /x')).toBe('/');
  expect(safeNext('/a b')).toBe('/');
});
