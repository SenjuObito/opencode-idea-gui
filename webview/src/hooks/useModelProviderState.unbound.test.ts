// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useModelProviderState } from './useModelProviderState';
import { sendBridgeEvent } from '../utils/bridge';

vi.mock('../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(),
  cardDebugLog: vi.fn(),
}));

const t = ((key: string, opts?: { defaultValue?: string }) => opts?.defaultValue ?? key) as any;

const configCalls = () => vi.mocked(sendBridgeEvent).mock.calls.filter(
  ([name]) => name === 'set_model' || name === 'set_mode' || name === 'set_reasoning_effort',
);

describe('useModelProviderState - unbound tab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  it('does not write model/mode/effort while the active tab has no session', () => {
    // The ref is authoritative: it is deliberately null for a brand-new tab,
    // while the `currentSessionId` prop still carries the previous tab's id
    // until React commits. Falling back to the prop is what rewrote the
    // outgoing session.
    const currentSessionIdRef = { current: null as string | null };
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t,
      currentSessionId: 'ses_previous',
      currentSessionIdRef,
    }));

    act(() => {
      result.current.handleModelSelect('opencode/new-model');
      result.current.handleModeSelect('plan');
      result.current.handleReasoningChange('high');
    });

    expect(configCalls()).toEqual([]);
  });

  it('scopes the same changes once the tab is bound', () => {
    const currentSessionIdRef = { current: 'ses_bound' as string | null };
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t,
      currentSessionId: null,
      currentSessionIdRef,
    }));

    act(() => {
      result.current.handleModelSelect('opencode/new-model');
      result.current.handleModeSelect('plan');
      result.current.handleReasoningChange('high');
    });

    const calls = configCalls();
    expect(calls).toContainEqual([
      'set_model',
      JSON.stringify({ model: 'opencode/new-model', sessionId: 'ses_bound' }),
    ]);
    expect(calls).toContainEqual([
      'set_mode',
      JSON.stringify({ sessionId: 'ses_bound', mode: 'plan' }),
    ]);
    expect(calls).toContainEqual([
      'set_reasoning_effort',
      JSON.stringify({ reasoningEffort: 'high', sessionId: 'ses_bound' }),
    ]);
  });
});
