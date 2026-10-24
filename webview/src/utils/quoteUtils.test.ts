import { describe, it, expect, vi, afterEach } from 'vitest';
import { formatAsMarkdownQuote, quoteToChatInput } from './quoteUtils';
import { registerHostEvents, unregisterHostEvents } from './hostEventRegistry';

describe('formatAsMarkdownQuote', () => {
  it('prefixes every line and adds a trailing newline', () => {
    expect(formatAsMarkdownQuote('line1\nline2')).toBe('> line1\n> line2\n');
  });

  it('trims trailing blank lines and normalizes CRLF', () => {
    expect(formatAsMarkdownQuote('a\r\nb\n\n')).toBe('> a\n> b\n');
  });
});

describe('quoteToChatInput', () => {
  afterEach(() => {
    unregisterHostEvents(['internal.chat.addQuote', 'chat.focus']);
  });

  it('sends the raw quote as a chip payload and focuses the input', () => {
    const addSnippet = vi.fn();
    const focus = vi.fn();
    registerHostEvents({ 'internal.chat.addQuote': addSnippet, 'chat.focus': focus });

    expect(quoteToChatInput('text')).toBe(true);
    expect(addSnippet).toHaveBeenCalledWith(JSON.stringify({ text: 'text' }));
    expect(focus).toHaveBeenCalled();
  });

  it('returns false for empty selections', () => {
    const addSnippet = vi.fn();
    registerHostEvents({ 'internal.chat.addQuote': addSnippet });
    expect(quoteToChatInput('   ')).toBe(false);
    expect(addSnippet).not.toHaveBeenCalled();
  });
});
