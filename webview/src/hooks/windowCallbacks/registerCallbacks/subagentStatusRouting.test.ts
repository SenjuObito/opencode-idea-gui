import { beforeEach, describe, expect, it, vi } from 'vitest';
import { registerWindowCallbacks } from '../registerCallbacks';
import { hostEventHandler } from '../../../utils/hostEventRegistry';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';

type Ref<T> = { current: T };
const ref = <T,>(value: T): Ref<T> => ({ current: value });

/** Core's marker payload: `{ subagent: "<json string>" }`. */
const payload = (toolUseId: string, status: string, extra: Record<string, unknown> = {}) => ({
  subagent: JSON.stringify({
    parentSessionId: 'ses_active',
    toolUseId,
    childSessionId: 'ses_child',
    status,
    description: 'Explore repo',
    ...extra,
  }),
});

describe('subagent run status routing', () => {
  let setTaskEvents: ReturnType<typeof vi.fn>;
  let updateBackgroundTab: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    delete (window as any).__sessionTabsStore;
    setTaskEvents = vi.fn();
    updateBackgroundTab = vi.fn();

    (window as any).__sessionTabsStore = {
      isActiveSession: (sessionId: string) => sessionId === 'ses_active',
      updateBackgroundTab,
    };

    const options = {
      currentSessionIdRef: ref<string | null>('ses_active'),
      setTaskEvents,
      streamingContentRef: ref(''),
      streamingThinkingRef: ref(''),
      isStreamingRef: ref(false),
      currentProviderRef: ref('opencode'),
      selectedModelRef: ref('default'),
    } as unknown as UseWindowCallbacksOptions;

    registerWindowCallbacks(options, vi.fn());
  });

  it('stores an inline terminal event in the active tab', () => {
    // Core publishes `subagent.status` for inline calls too (the parent tool
    // result is the child's final report), so the card's status no longer
    // depends on transcript timing.
    hostEventHandler('subagent.status')?.(payload('call_inline', 'completed'));

    expect(updateBackgroundTab).not.toHaveBeenCalled();
    expect(setTaskEvents).toHaveBeenCalledTimes(1);
    const updater = setTaskEvents.mock.calls[0][0] as (prev: Record<string, any>) => Record<string, any>;
    const next = updater({});
    expect(next.call_inline).toMatchObject({
      toolUseId: 'call_inline',
      status: 'completed',
      agentId: 'ses_child',
    });
  });

  it('maps a stopped inline run to the same event vocabulary', () => {
    hostEventHandler('subagent.status')?.(payload('call_inline', 'stopped'));

    const updater = setTaskEvents.mock.calls[0][0] as (prev: Record<string, any>) => Record<string, any>;
    expect(updater({}).call_inline.status).toBe('stopped');
  });

  it('routes a background tab event into that tab only', () => {
    hostEventHandler('subagent.status')?.(
      payload('call_bg', 'completed', { parentSessionId: 'ses_bg' }),
      { sessionId: 'ses_bg' } as any,
    );

    expect(setTaskEvents).not.toHaveBeenCalled();
    expect(updateBackgroundTab).toHaveBeenCalledTimes(1);

    const [predicate, updater] = updateBackgroundTab.mock.calls[0] as [
      (tab: { sessionId?: string }) => boolean,
      (tab: Record<string, any>) => void,
    ];
    expect(predicate({ sessionId: 'ses_bg' })).toBe(true);
    expect(predicate({ sessionId: 'ses_active' })).toBe(false);

    const tab: Record<string, any> = { sessionId: 'ses_bg', taskEvents: {} };
    updater(tab);
    expect(tab.taskEvents.call_bg).toMatchObject({ toolUseId: 'call_bg', status: 'completed' });
  });

  it('ignores a malformed status payload', () => {
    expect(() => hostEventHandler('subagent.status')?.({ subagent: '{not json' })).not.toThrow();
    expect(setTaskEvents).not.toHaveBeenCalled();
    expect(updateBackgroundTab).not.toHaveBeenCalled();
  });
});
