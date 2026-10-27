import { beforeEach, describe, expect, it, vi } from 'vitest';
import { registerWindowCallbacks } from '../registerCallbacks';
import { hostEventHandler } from '../../../utils/hostEventRegistry';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { getTabStore } from '../../../store/tabStore';
import { seedTabStore } from '../../../test/seedTabStore';

type Ref<T> = { current: T };
const ref = <T,>(value: T): Ref<T> => ({ current: value });

const payload = (sessionId: string, toolUseId = 'tool-1') => JSON.stringify({
  sessionId,
  provider: 'opencode',
  toolUseId,
  agentId: 'agent-1',
  success: true,
  completed: true,
  status: 'completed',
  messages: [],
});

describe('StatusPanel subagent history routing', () => {
  let setSubagentHistories: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    delete (window as any).onSubagentHistoryLoaded;

    setSubagentHistories = vi.fn();
    seedTabStore([
      { id: 'tab-active', sessionId: 'ses_active' },
      { id: 'tab-bg', sessionId: 'ses_bg' },
    ], 'tab-active');

    const options = {
      currentSessionIdRef: ref<string | null>('ses_active'),
      setSubagentHistories,
      streamingContentRef: ref(''),
      streamingThinkingRef: ref(''),
      isStreamingRef: ref(false),
      currentProviderRef: ref('opencode'),
      selectedModelRef: ref('default'),
    } as unknown as UseWindowCallbacksOptions;

    registerWindowCallbacks(options, vi.fn());
  });

  it('writes the active tab transcript into active state', () => {
    hostEventHandler('subagent.history')?.(payload('ses_active'));

    expect(setSubagentHistories).toHaveBeenCalledTimes(1);
    const updater = setSubagentHistories.mock.calls[0][0] as (prev: Record<string, any>) => Record<string, any>;
    expect(updater({})['tool-1'].sessionId).toBe('ses_active');
  });

  it('writes a background tab transcript into that tab only', () => {
    hostEventHandler('subagent.history')?.(payload('ses_bg', 'tool-bg'));

    expect(setSubagentHistories).not.toHaveBeenCalled();
    const background = getTabStore().findById('tab-bg') as Record<string, any>;
    expect(background.subagentHistories['tool-bg'].sessionId).toBe('ses_bg');
  });

  it('accepts the named subagent.history payload', () => {
    hostEventHandler('subagent.history')?.({ history: JSON.parse(payload('ses_active')) });

    expect(setSubagentHistories).toHaveBeenCalledTimes(1);
    const updater = setSubagentHistories.mock.calls[0][0] as (prev: Record<string, any>) => Record<string, any>;
    expect(updater({})['tool-1'].sessionId).toBe('ses_active');
  });

  it('drops a transcript whose session has no tab instead of leaking it', () => {
    hostEventHandler('subagent.history')?.(payload('ses_unknown', 'tool-unknown'));

    expect(setSubagentHistories).not.toHaveBeenCalled();
    expect(getTabStore().findById('tab-active')?.subagentHistories).toBeUndefined();
  });

  it('never demotes a terminal transcript with a late running snapshot', () => {
    // The expand-time read and the post-terminal refresh can be in flight at
    // once; the expand-time answer may be the older, still-running snapshot.
    hostEventHandler('subagent.history')?.(payload('ses_active'));
    const firstUpdater = setSubagentHistories.mock.calls[0][0] as (prev: Record<string, any>) => Record<string, any>;
    const completed = firstUpdater({});
    expect(completed['tool-1'].status).toBe('completed');

    setSubagentHistories.mockClear();
    hostEventHandler('subagent.history')?.(JSON.stringify({
      ...JSON.parse(payload('ses_active')),
      status: 'running',
      completed: false,
    }));
    const secondUpdater = setSubagentHistories.mock.calls[0][0] as (prev: Record<string, any>) => Record<string, any>;
    const merged = secondUpdater(completed);

    expect(merged['tool-1'].status).toBe('completed');
  });
});
