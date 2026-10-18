import { describe, it, expect } from 'vitest';
import {
  registerQuote,
  getQuote,
  removeQuote,
  makeQuoteToken,
  quoteTokenLength,
  hasQuoteToken,
  quotePreview,
  createQuoteChipElement,
  expandQuoteTokens,
  pruneQuoteRegistry,
  exportQuotes,
  restoreQuotes,
} from './quoteRegistry';

describe('quoteRegistry token round-trip', () => {
  it('registers a quote and expands its token into a Markdown blockquote', () => {
    const id = registerQuote('line one\nline two');
    const token = makeQuoteToken(id);

    expect(hasQuoteToken(`before ${token} after`)).toBe(true);
    expect(quoteTokenLength(id)).toBe(token.length);

    const expanded = expandQuoteTokens(`ask: ${token}please`);
    expect(expanded).toBe('ask: > line one\n> line two\nplease');
  });

  it('drops unknown tokens on expand', () => {
    const id = registerQuote('gone');
    const token = makeQuoteToken(id);
    removeQuote(id);
    expect(getQuote(id)).toBeUndefined();
    expect(expandQuoteTokens(`x${token}y`)).toBe('xy');
  });

  it('expands multiple tokens in one pass', () => {
    const first = makeQuoteToken(registerQuote('aaa'));
    const second = makeQuoteToken(registerQuote('bbb'));
    expect(expandQuoteTokens(`${first}${second}`)).toBe('> aaa\n> bbb\n');
  });
});

describe('pruneQuoteRegistry', () => {
  it('drops entries whose ids are no longer active and keeps the rest', () => {
    const keep = registerQuote('still here');
    const stale = registerQuote('deleted chip');

    pruneQuoteRegistry(new Set([keep]));

    expect(getQuote(keep)).toBeDefined();
    expect(getQuote(stale)).toBeUndefined();
  });

  it('prunes everything when the input was cleared', () => {
    const a = registerQuote('a');
    const b = registerQuote('b');

    pruneQuoteRegistry(new Set());

    expect(getQuote(a)).toBeUndefined();
    expect(getQuote(b)).toBeUndefined();
  });

  it('keeps entries in preservedIds even if not in activeIds', () => {
    const active = registerQuote('active');
    const preserved = registerQuote('in other tab draft');
    const stale = registerQuote('stale');

    pruneQuoteRegistry(new Set([active]), new Set([preserved]));

    expect(getQuote(active)).toBeDefined();
    expect(getQuote(preserved)).toBeDefined();
    expect(getQuote(stale)).toBeUndefined();
  });
});

describe('exportQuotes and restoreQuotes', () => {
  it('exports and restores quotes across tab switching', () => {
    const q1 = registerQuote('quote 1 content');
    const q2 = registerQuote('quote 2 content');
    const token1 = makeQuoteToken(q1);

    const exportedSubset = exportQuotes(`some text with ${token1}`);
    expect(exportedSubset[q1]).toEqual({ text: 'quote 1 content' });
    expect(exportedSubset[q2]).toBeUndefined();

    // Clear registry
    pruneQuoteRegistry(new Set());
    expect(getQuote(q1)).toBeUndefined();

    // Restore exported quote
    restoreQuotes(exportedSubset);
    expect(getQuote(q1)).toEqual({ text: 'quote 1 content' });
  });
});

describe('quotePreview', () => {
  it('collapses whitespace and truncates long text', () => {
    expect(quotePreview('short\n  text')).toBe('short text');
    expect(quotePreview('x'.repeat(60), 10)).toBe(`${'x'.repeat(10)}…`);
  });
});

describe('createQuoteChipElement', () => {
  it('builds a non-editable chip carrying the id and a preview', () => {
    const id = registerQuote('the quoted body');
    const chip = createQuoteChipElement(id, { text: 'the quoted body' });

    expect(chip.classList.contains('quote-tag')).toBe(true);
    expect(chip.getAttribute('contenteditable')).toBe('false');
    expect(chip.getAttribute('data-quote-id')).toBe(id);
    expect(chip.querySelector('.quote-tag-text')?.textContent).toBe('the quoted body');
    expect(chip.querySelector('.quote-tag-close')?.textContent).toBe('×');
  });
});
