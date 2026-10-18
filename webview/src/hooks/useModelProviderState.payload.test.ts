// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import {
  buildModelSelectionPayload,
  buildReasoningEffortPayload,
} from './useModelProviderState';

/**
 * Payload-scoping tests.
 *
 * The host resolves a payload without a session id against "the window's current
 * session". In a multi-tab webview that drifts from the tab the user is looking
 * at, so the change lands on the wrong OpencodeSession while the UI reports
 * success — exactly the "I switched the model and nothing changed" symptom.
 * Tabs without a bound session must still use the legacy bare-string form so a
 * brand-new conversation keeps working.
 */
describe('buildModelSelectionPayload', () => {
  it('scopes the change to the given session', () => {
    const payload = buildModelSelectionPayload('opencode/mimo-v2.6-flash-free', 'ses_a');
    expect(JSON.parse(payload)).toEqual({
      model: 'opencode/mimo-v2.6-flash-free',
      sessionId: 'ses_a',
    });
  });

  it('carries the silent flag used by tab-switch restores', () => {
    const payload = buildModelSelectionPayload('opencode/x', 'ses_a', { silent: true });
    expect(JSON.parse(payload)).toEqual({
      model: 'opencode/x',
      sessionId: 'ses_a',
      silent: true,
    });
  });

  it('keeps the legacy bare-string form when no session is bound yet', () => {
    expect(buildModelSelectionPayload('opencode/x', null)).toBe('opencode/x');
    expect(buildModelSelectionPayload('opencode/x', undefined)).toBe('opencode/x');
    // `silent` must not force JSON when there is nothing to scope to.
    expect(buildModelSelectionPayload('opencode/x', null, { silent: true })).toBe('opencode/x');
  });
});

describe('buildReasoningEffortPayload', () => {
  it('scopes the effort to the given session', () => {
    const payload = buildReasoningEffortPayload('high', 'ses_b');
    expect(JSON.parse(payload)).toEqual({ reasoningEffort: 'high', sessionId: 'ses_b' });
  });

  it('keeps the legacy bare-string form when no session is bound yet', () => {
    expect(buildReasoningEffortPayload('high', null)).toBe('high');
    expect(buildReasoningEffortPayload('medium', undefined)).toBe('medium');
  });
});
