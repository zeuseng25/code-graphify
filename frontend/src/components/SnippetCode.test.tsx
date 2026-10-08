import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SnippetCode } from './SnippetCode';

describe('SnippetCode', () => {
  it('wraps a long snippet instead of widening its table', () => {
    const text = 'CorrelationId.get()'.repeat(20);
    render(<MantineProvider><SnippetCode>{text}</SnippetCode></MantineProvider>);
    const code = screen.getByText(text);
    expect(code.style.whiteSpace).toBe('pre-wrap');
    expect(code.style.wordBreak).toBe('break-word');
  });
});
