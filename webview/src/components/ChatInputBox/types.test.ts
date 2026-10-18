import { describe, expect, it } from 'vitest';
import {
  OPENCODE_DEFAULT_MODEL_ID,
  OPENCODE_MODELS,
  getAvailableReasoningLevels,
} from './types';

describe('OPENCODE_DEFAULT_MODEL_ID', () => {
  it('is defined and non-empty', () => {
    expect(OPENCODE_DEFAULT_MODEL_ID).toBe('opencode-default');
  });
});

describe('OPENCODE_MODELS', () => {
  it('contains the default model', () => {
    expect(OPENCODE_MODELS.some((m) => m.id === OPENCODE_DEFAULT_MODEL_ID)).toBe(true);
  });

  it('has valid structure for all models', () => {
    for (const model of OPENCODE_MODELS) {
      expect(model.id).toBeTruthy();
      expect(model.label).toBeTruthy();
    }
  });
});

describe('getAvailableReasoningLevels', () => {
  it('keeps the legacy list when variants are unknown, headed by Default', () => {
    // opencode v1 ships no variant catalog: keep the previous behaviour, but
    // Default is still valid there (it just omits the field).
    const levels = getAvailableReasoningLevels('opencode', 'some-model', undefined);
    expect(levels.map((l) => l.id)).toEqual([
      'default', 'low', 'medium', 'high', 'xhigh', 'max',
    ]);
  });

  it('returns nothing when the model explicitly has no variants', () => {
    // Distinct from `undefined`: the selector hides. Default is not injected
    // either — a dropdown whose only option is "Default" says nothing useful,
    // and hiding tells the truth: this model cannot be tuned.
    expect(getAvailableReasoningLevels('opencode', 'mimo-v2.6-flash-free', [])).toEqual([]);
  });

  it('filters by modelVariants when provided', () => {
    const levels = getAvailableReasoningLevels('claude', 'claude-opus-4-8', ['low', 'medium', 'high']);
    expect(levels.map((l) => l.id)).toEqual(['default', 'low', 'medium', 'high']);
  });

  it('renders only the variants the model offers', () => {
    const levels = getAvailableReasoningLevels('opencode', 'deepseek', ['low', 'high', 'max']);
    expect(levels.map((l) => l.id)).toEqual(['default', 'low', 'high', 'max']);
    // Known ids keep their translated label and icon.
    expect(levels[1].label).toBe('Low');
  });

  it('generates entries for catalog variants outside the static list', () => {
    const levels = getAvailableReasoningLevels('opencode', 'nemotron', ['none', 'xhigh']);
    expect(levels.map((l) => l.id)).toEqual(['default', 'none', 'xhigh']);
    // Unknown ids fall back to the raw id so the option is still selectable.
    expect(levels[1].label).toBe('none');
  });

  it('always offers Default first, even for a model with a single variant', () => {
    // `default` is a real state (a session with no variant reports back
    // `variant: "default"`) but no opencode model lists it in its catalog, so
    // the UI injects it. This is what made it invisible before.
    const levels = getAvailableReasoningLevels('opencode', 'glm', ['high']);
    expect(levels[0].id).toBe('default');
    expect(levels.map((l) => l.id)).toEqual(['default', 'high']);
  });

  it('does not duplicate Default when a catalog happens to list it', () => {
    const levels = getAvailableReasoningLevels('opencode', 'odd-model', ['default', 'low']);
    expect(levels.map((l) => l.id)).toEqual(['default', 'low']);
  });
});
