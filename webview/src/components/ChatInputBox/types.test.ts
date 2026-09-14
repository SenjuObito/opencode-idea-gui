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
  it('returns all levels when no modelVariants are provided', () => {
    const levels = getAvailableReasoningLevels('claude', 'claude-opus-4-8');
    expect(levels.length).toBe(5);
  });

  it('filters by modelVariants when provided', () => {
    const levels = getAvailableReasoningLevels('claude', 'claude-opus-4-8', ['low', 'medium', 'high']);
    expect(levels.map((l) => l.id)).toEqual(['low', 'medium', 'high']);
  });

  it('returns all levels when modelVariants is empty', () => {
    const levels = getAvailableReasoningLevels('claude', 'claude-opus-4-8', []);
    expect(levels.length).toBe(5);
  });
});
