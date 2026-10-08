import { Code } from '@mantine/core';

/** A source snippet in a table: wraps (keeping indentation) so a long line never pushes the table off the page. */
export function SnippetCode({ children }: { children: string }) {
  return <Code style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{children}</Code>;
}
