/**
 * turnEnd.test.ts
 *
 * `onTurnEnd` is the daemon's authoritative, per-session turn terminal. These
 * cases pin down the two properties the old terminal signals lacked:
 *
 * 1. it is addressed to the session named on the wire (not "the active tab");
 * 2. it is idempotent on (sessionId, turnId), so it is safe for it to arrive
 *    alongside — or instead of — `onStreamEnd`.
 *
 * Together those are what stop a tab from spinning forever after the user
 * presses stop, which is exactly the failure the old pipeline could not recover
 * from: its terminal was suppressible and had no retry.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { registerStreamingCallbacks } from './streamingCallbacks';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { ClaudeMessage } from '../../../types';
import { hostEventHandler } from '../../../utils/hostEventRegistry';
import { sessionEvent } from '../../../utils/testEvents';
import { getTabStore } from '../../../store/tabStore';
import { seedTabStore } from '../../../test/seedTabStore';

type Ref<T> = { current: T };
const ref = <T,>(value: T): Ref<T> => ({ current: value });

function createHarness() {
  let messages: ClaudeMessage[] = [];

  const setStreamingActive = vi.fn();
  const setLoading = vi.fn();
  const setLoadingStartTime = vi.fn();
  const setIsThinking = vi.fn();

  const options = {
    streamingContentRef: ref(''),
    streamingThinkingRef: ref(''),
    isStreamingRef: ref(true),
    useBackendStreamingRenderRef: ref(false),
    autoExpandedThinkingKeysRef: ref(new Set<string>()),
    streamingMessageIndexRef: ref(-1),
    streamingTurnIdRef: ref(1),
    turnIdCounterRef: ref(1),
    lastContentUpdateRef: ref(0),
    contentUpdateTimeoutRef: ref<number | null>(null),
    lastThinkingUpdateRef: ref(0),
    thinkingUpdateTimeoutRef: ref<number | null>(null),
    currentProviderRef: ref('claude'),
    setMessages: (updater: ClaudeMessage[] | ((prev: ClaudeMessage[]) => ClaudeMessage[])) => {
      messages = typeof updater === 'function' ? updater(messages) : updater;
    },
    setStreamingActive,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    setExpandedThinking: () => {},
    getOrCreateStreamingAssistantIndex: () => -1,
    patchAssistantForStreaming: (message: ClaudeMessage) => message,
  } as unknown as UseWindowCallbacksOptions;

  registerStreamingCallbacks(options);
  return {
    options,
    setStreamingActive,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    getMessages: () => messages,
  };
}

describe('onTurnEnd — session-scoped, idempotent terminal', () => {
  beforeEach(() => {
    seedTabStore([{ id: 'tab-a', sessionId: 'ses_a' }], 'tab-a');
    window.__deniedToolIds = undefined;
  });

  it('clears loading and streaming for the session named on the wire', () => {
    const h = createHarness();

    hostEventHandler('turn.settled')!(...sessionEvent('ses_a', { terminal: JSON.stringify({ turnId: 7, reason: 'idle' }) }));

    expect(h.setStreamingActive).toHaveBeenCalledWith(false);
    expect(h.setLoading).toHaveBeenCalledWith(false);
    expect(h.setLoadingStartTime).toHaveBeenCalledWith(null);
    expect(h.setIsThinking).toHaveBeenCalledWith(false);
  });

  it('is dropped when it carries no session identity', () => {
    const h = createHarness();

    // An empty id must never be read as "whichever tab is on screen".
    hostEventHandler('turn.settled')!(...sessionEvent(null, { value: '', value: JSON.stringify({ turnId: 7 }) }));

    expect(h.setLoading).not.toHaveBeenCalled();
  });

  it('ignores a repeat of the same (sessionId, turnId)', () => {
    const h = createHarness();

    hostEventHandler('turn.settled')!(...sessionEvent('ses_a', { terminal: JSON.stringify({ turnId: 7 }) }));
    hostEventHandler('turn.settled')!(...sessionEvent('ses_a', { terminal: JSON.stringify({ turnId: 7 }) }));

    expect(h.setLoading).toHaveBeenCalledTimes(1);
  });

  it('accepts a later turn of the same session', () => {
    const h = createHarness();

    hostEventHandler('turn.settled')!(...sessionEvent('ses_a', { terminal: JSON.stringify({ turnId: 7 }) }));
    hostEventHandler('turn.settled')!(...sessionEvent('ses_a', { terminal: JSON.stringify({ turnId: 8 }) }));

    expect(h.setLoading).toHaveBeenCalledTimes(2);
  });

  it('does not confuse two sessions that use the same turnId', () => {
    // No tab strip: both sessions are foreground, so the only thing that can
    // suppress the second terminal is a key missing the session id.
    seedTabStore([], '');
    const h = createHarness();

    hostEventHandler('turn.settled')!(...sessionEvent('ses_a', { terminal: JSON.stringify({ turnId: 7 }) }));
    hostEventHandler('turn.settled')!(...sessionEvent('ses_b', { terminal: JSON.stringify({ turnId: 7 }) }));

    expect(h.setLoading).toHaveBeenCalledTimes(2);
  });

  it('routes to a background tab when that session is not the active one', () => {
    const h = createHarness();
    seedTabStore([
      { id: 'tab-a', sessionId: 'ses_a' },
      { id: 'tab-bg', sessionId: 'ses_bg' },
    ], 'tab-a');

    hostEventHandler('turn.settled')!(...sessionEvent('ses_bg', { terminal: JSON.stringify({ turnId: 3 }) }));

    expect(getTabStore().findById('tab-bg')?.streamingActive).toBe(false);
    expect(getTabStore().findById('tab-bg')?.loading).toBe(false);
    // And the active session's own state must be left alone.
    expect(h.setLoading).not.toHaveBeenCalled();
  });

  it('survives a payload that is not JSON — identity is what matters', () => {
    const h = createHarness();

    expect(() => hostEventHandler('turn.settled')!(...sessionEvent('ses_a', { value: 'not-json' }))).not.toThrow();
    expect(h.setLoading).toHaveBeenCalledWith(false);
  });
});
