import { afterEach, expect, it, vi } from 'vitest';
import { filenameOf, saveBlob } from './download';

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

it('reads the file name from Content-Disposition', () => {
  expect(filenameOf('attachment; filename="impact-7.csv"')).toBe('impact-7.csv');
  expect(filenameOf('attachment; filename=plain.csv')).toBe('plain.csv');
  expect(filenameOf("attachment; filename*=UTF-8''etki%20raporu.csv")).toBe('etki raporu.csv');
  expect(filenameOf(null)).toBeNull();
});

it('rejects empty, foreign and path-like names', () => {
  expect(filenameOf('attachment; filename=""')).toBeNull();
  expect(filenameOf('attachment; filename="../etc/x.csv"')).toBe('x.csv');
  expect(filenameOf("attachment; filename*=UTF-8''..%2F..%2Fa.csv")).toBe('a.csv');
  expect(filenameOf('attachment; xfilename=evil.csv')).toBeNull();
  expect(filenameOf('attachment; filename=a.csv; filename*=UTF-8\'\'b.csv')).toBe('b.csv');
});

it('saves a blob through a temporary link', () => {
  vi.useFakeTimers();
  URL.createObjectURL = vi.fn(() => 'blob:test');
  URL.revokeObjectURL = vi.fn();
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

  saveBlob(new Blob(['a,b']), 'x.csv');

  expect(click).toHaveBeenCalledOnce();
  expect(URL.revokeObjectURL).not.toHaveBeenCalled();
  vi.runAllTimers();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test');
});
