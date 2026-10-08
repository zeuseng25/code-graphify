import { Fragment } from 'react';

/** Where a word starts inside a camel-case segment: a capital after a lower-case letter or digit. */
const WORD_START = /(?<=[a-z0-9])(?=[A-Z])/;

/**
 * A qualified name (package, class or method) that may wrap only after its dots or between the words of a camel-case
 * name, so a narrow column breaks `com.mb.api.OrderController` as `com.mb.api.Order|Controller` instead of inside a
 * word. Acronyms (`HTTPClient`) stay whole. `<wbr>` adds no characters, so a copied name stays exact.
 */
export function Fqn({ value }: { value: string }) {
  const segments = value.split('.');
  return (
    <>
      {segments.map((segment, index) => (
        <Fragment key={index}>
          {segment.split(WORD_START).map((word, wordIndex) => (
            <Fragment key={wordIndex}>
              {wordIndex > 0 && <wbr />}
              {word}
            </Fragment>
          ))}
          {index < segments.length - 1 && <>.<wbr /></>}
        </Fragment>
      ))}
    </>
  );
}
