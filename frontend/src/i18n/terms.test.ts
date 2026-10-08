import { expect, it } from 'vitest';
import { tr } from './tr';

/** Software terms stay in English (user ruling); this guards against translating them back. */
// \b is ASCII-only in JavaScript, so Turkish letters need Unicode-aware boundaries
const TRANSLATED = /(?<!\p{L})(sınıf|metot|metod|yapıcı|arayüz|alan okuma|alan yazma|imza|paket|modül|kütüphane|bağımlılık)/iu;

function texts(value: unknown, path = 'tr'): [string, string][] {
  if (typeof value === 'string') {
    return [[path, value]];
  }
  if (typeof value === 'function') {
    return [[path, String((value as (...args: unknown[]) => unknown)(...['1', '2', '3']))]];
  }
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, child]) => texts(child, `${path}.${key}`));
  }
  return [];
}

it('keeps software terms in English in every UI text', () => {
  const offending = texts(tr).filter(([, text]) => TRANSLATED.test(text));
  expect(offending).toEqual([]);
});

it('labels the Java element kinds as developers write them', () => {
  expect(tr.enums.symbolKind).toMatchObject({
    CLASS: 'Class', INTERFACE: 'Interface', ENUM: 'Enum', RECORD: 'Record', ANNOTATION_TYPE: 'Annotation',
    METHOD: 'Method', CONSTRUCTOR: 'Constructor', FIELD: 'Field',
  });
});
