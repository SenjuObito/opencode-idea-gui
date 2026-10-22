import { beforeEach, describe, expect, it, vi } from 'vitest';
import { registerWindowCallbacks } from '../registerCallbacks';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';

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
  let updateTab: ReturnType<typeof vi.fn>;
  let getTabIdForSession: ReturnType<typeof vi.fn>;
  let isActiveTab: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    delete (window as any).onSubagentHistoryLoaded;
    delete (window as any).__sessionTabsStore;

    setSubagentHistories = vi.fn();
    updateTab = vi.fn(() => true);
    getTabIdForSession = vi.fn((sessionId: string) => {
      if (sessionId === 'ses_active') return 'tab-active';
      if (sessionId === 'ses_bg') return 'tab-bg';
      return null;
    });
    isActiveTab = vi.fn((tabId: string) => tabId === 'tab-active');

    (window as any).__sessionTabsStore = {
      getTabIdForSession,
      isActiveTab,
      updateTab,
    };

    const options = {
      currentSessionIdRef: ref<string | null>('ses_active'),
      setSubagentHistories,
      streamingContentRef: ref(''),
      streamingThinkingRef: ref(''),
      isStreamingRef: ref(false),
      currentProviderRef: ref('opencode'),
      selectedModelRef: ref('default'),
    } as unknown as UseWindowCallbacksOptions;

    registerWindowCallbacks(options, ref(vi.fn() as any), vi.fn());
  });

  it('writes the active tab transcript into active state', () => {
    window.onSubagentHistoryLoaded?.(payload('ses_active'));

    expect(updateTab).not.toHaveBeenCalled();
    expect(setSubagentHistories).toHaveBeenCalledTimes(1);
    const updater = setSubagentHistories.mock.calls[0][0] as (prev: Record<string, any>) => Record<string, any>;
    expect(updater({})['tool-1'].sessionId).toBe('ses_active');
  });

  it('writes a background tab transcript into that tab only', () => {
    window.onSubagentHistoryLoaded?.(payload('ses_bg', 'tool-bg'));

    expect(setSubagentHistories).not.toHaveBeenCalled();
    expect(updateTab).toHaveBeenCalledTimes(1);
    const [tabId, updater] = updateTab.mock.calls[0];
    expect(tabId).toBe('tab-bg');
    const tab: Record<string, any> = { subagentHistories: {} };
    updater(tab);
    expect(tab.subagentHistories['tool-bg'].sessionId).toBe('ses_bg');
  });

  it('accepts the named subagent.history payload', () => {
    window.onSubagentHistoryLoaded?.({ history: JSON.parse(payload('ses_active')) });

    expect(setSubagentHistories).toHaveBeenCalledTimes(1);
    const updater = setSubagentHistories.mock.calls[0][0] as (prev: Record<string, any>) => Record<string, any>;
    expect(updater({})['tool-1'].sessionId).toBe('ses_active');
  });

  it('drops a transcript whose session has no tab instead of leaking it', () => {
    window.onSubagentHistoryLoaded?.(payload('ses_unknown', 'tool-unknown'));

    expect(updateTab).not.toHaveBeenCalled();
    expect(setSubagentHistories).not.toHaveBeenCalled();
  });
});
