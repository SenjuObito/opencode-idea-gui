import { act, renderHook } from '@testing-library/react';
import { useMessageSender } from './useMessageSender';
import type { UseMessageSenderOptions } from './useMessageSender';

describe('useMessageSender - /context command', () => {
  const t = ((key: string, opts?: any) => opts?.defaultValue ?? key) as any;

  const createOptions = (overrides: Partial<UseMessageSenderOptions> = {}): UseMessageSenderOptions => ({
    t,
    addToast: vi.fn(),
    currentProvider: 'claude',
    selectedModel: 'claude-opus-4-8',
    permissionMode: 'default',
    reasoningEffort: 'high',
    daemonStatusLoaded: true,
    currentSdkInstalled: true,
    sentAttachmentsRef: { current: new Map() },
    chatInputRef: { current: null },
    messagesContainerRef: { current: null },
    isUserAtBottomRef: { current: true },
    userPausedRef: { current: false },
    isStreamingRef: { current: false },
    streamingContentRef: { current: '' },
    streamingThinkingRef: { current: '' },
    contentUpdateTimeoutRef: { current: null },
    thinkingUpdateTimeoutRef: { current: null },
    setMessages: vi.fn(),
    setLoading: vi.fn(),
    setLoadingStartTime: vi.fn(),
    setStreamingActive: vi.fn(),
    setSettingsInitialTab: vi.fn(),
    setCurrentView: vi.fn(),
    forceCreateNewSession: vi.fn(),
    handleModeSelect: vi.fn(),
    longContextEnabled: false,
    openContextUsageDialog: vi.fn(),
    closeContextUsageDialog: vi.fn().mockReturnValue(true),
    ...overrides,
  });

  // Commands travel in the v2 envelope; the content lives in `payload` and the
  // identity in the envelope itself.
  const getEnvelope = (eventName: string) => {
    const calls = (window.sendToJava as any).mock.calls.map((call: [string]) => call[0]);
    const prefix = `${eventName}:`;
    const sendCall = calls.find((call: string) => call.startsWith(prefix));
    expect(sendCall).toBeTruthy();
    return JSON.parse(sendCall!.substring(prefix.length));
  };

  const getBridgePayload = (eventName: string) => getEnvelope(eventName).payload;

  beforeEach(() => {
    window.sendToJava = vi.fn();
    (window as unknown as { __sessionTabsStore?: unknown }).__sessionTabsStore = {
      getActiveTabId: () => 'tab-1',
      getActiveTabAutoOpenFileEnabled: () => false,
    };
  });

  afterEach(() => {
    delete (window as unknown as { __sessionTabsStore?: unknown }).__sessionTabsStore;
  });

  it('sends get_context_usage with base model when longContext is disabled', () => {
    const opts = createOptions({
      selectedModel: 'claude-opus-4-8',
      longContextEnabled: false,
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    const call = (window.sendToJava as any).mock.calls[0][0] as string;
    expect(call).toMatch(/^get_context_usage:/);

    const payload = JSON.parse(call.substring('get_context_usage:'.length));
    expect(payload.model).toBe('claude-opus-4-8');
    expect(payload.requestId).toBeTruthy();
  });

  it('sends get_context_usage with selected model when longContext is enabled', () => {
    const opts = createOptions({
      selectedModel: 'claude-opus-4-8',
      longContextEnabled: true,
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    const call = (window.sendToJava as any).mock.calls[0][0] as string;
    const payload = JSON.parse(call.substring('get_context_usage:'.length));
    expect(payload.model).toBe('claude-opus-4-8');
  });

  it('opens dialog with loading state before sending bridge event', () => {
    const openContextUsageDialog = vi.fn();
    const opts = createOptions({ openContextUsageDialog });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(openContextUsageDialog).toHaveBeenCalledTimes(1);
    expect(openContextUsageDialog).toHaveBeenCalledWith(
      expect.any(String),
      true, // loading = true
    );
    // Dialog opened BEFORE bridge event sent
    expect(openContextUsageDialog.mock.invocationCallOrder[0]).toBeLessThan(
      (window.sendToJava as any).mock.invocationCallOrder[0],
    );
  });

  it('shows warning toast and does not send bridge event for Codex provider', () => {
    const addToast = vi.fn();
    const opts = createOptions({
      currentProvider: 'codex',
      addToast,
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(window.sendToJava).not.toHaveBeenCalled();
    expect(addToast).toHaveBeenCalledTimes(1);
    expect(addToast).toHaveBeenCalledWith(
      expect.stringContaining('Claude'),
      'warning',
    );
  });

  it('closes dialog with error toast when bridge is unavailable', () => {
    // Don't set window.sendToJava → bridge unavailable
    delete (window as any).sendToJava;

    const addToast = vi.fn();
    const closeContextUsageDialog = vi.fn().mockReturnValue(true);
    const opts = createOptions({ addToast, closeContextUsageDialog });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('/context');
    });

    expect(closeContextUsageDialog).toHaveBeenCalledTimes(1);
    expect(addToast).toHaveBeenCalledWith(
      expect.any(String),
      'error',
    );
  });

  it('includes explicit Claude high reasoning effort in plain message payload', () => {
    const opts = createOptions({
      currentProvider: 'claude',
      selectedModel: 'claude-opus-4-8',
      reasoningEffort: 'high',
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello');
    });

    const payload = getBridgePayload('session.send');
    expect(payload.reasoningEffort).toBe('high');
  });

  it('includes explicit Claude high reasoning effort in attachment message payload', () => {
    const opts = createOptions({
      currentProvider: 'claude',
      selectedModel: 'claude-opus-4-8',
      reasoningEffort: 'high',
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello', [{
        id: 'att-1',
        fileName: 'note.txt',
        mediaType: 'text/plain',
        data: 'aGVsbG8=',
      }]);
    });

    const payload = getBridgePayload('session.send');
    expect(payload.reasoningEffort).toBe('high');
  });

  it('passes through reasoning effort consistently', () => {
    const opts = createOptions({
      currentProvider: 'claude',
      selectedModel: 'claude-haiku-4-5',
      reasoningEffort: 'low',
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello');
    });

    const payload = getBridgePayload('session.send');
    expect(payload.reasoningEffort).toBe('low');
  });

  it('includes explicit non-default Claude reasoning effort in plain message payload', () => {
    const opts = createOptions({
      reasoningEffort: 'low',
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello');
    });

    const payload = getBridgePayload('session.send');
    expect(payload.reasoningEffort).toBe('low');
  });

  it('includes explicit non-default Claude reasoning effort in attachment message payload', () => {
    const opts = createOptions({
      reasoningEffort: 'low',
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello', [{
        id: 'att-1',
        fileName: 'note.txt',
        mediaType: 'text/plain',
        data: 'aGVsbG8=',
      }]);
    });

    const payload = getBridgePayload('session.send');
    expect(payload.reasoningEffort).toBe('low');
  });

  // A tab without a bound session has no target for `set_model`; its model has
  // to travel with the message so the host can apply it when creating the
  // session. Otherwise the new session inherits the previous tab's model.
  it('carries the selected model in the plain message payload', () => {
    const opts = createOptions({ selectedModel: 'opencode/new-model' });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello');
    });

    const payload = getBridgePayload('session.send');
    expect(payload.model).toBe('opencode/new-model');
  });

  it('carries the selected model in the attachment message payload', () => {
    const opts = createOptions({ selectedModel: 'opencode/new-model' });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello', [{
        id: 'att-1',
        fileName: 'note.txt',
        mediaType: 'text/plain',
        data: 'aGVsbG8=',
      }]);
    });

    const payload = getBridgePayload('session.send');
    expect(payload.model).toBe('opencode/new-model');
  });

  // The first send of a new tab is the only point where its model can reach the
  // host: the tab has no session yet, and a stale `currentSessionId` prop must
  // not make the payload target the previous conversation.
  it('sends a new tab\'s model with tabId and no stale sessionId', () => {
    (window as unknown as { __sessionTabsStore?: unknown }).__sessionTabsStore = {
      getActiveTabId: () => 'tab-new',
    };
    const opts = createOptions({
      selectedModel: 'opencode/new-model',
      permissionMode: 'plan',
      reasoningEffort: 'high',
      currentSessionId: 'ses_previous',
      currentSessionIdRef: { current: null },
    });

    const { result } = renderHook(() => useMessageSender(opts));

    act(() => {
      result.current.handleSubmit('hello');
    });

    const envelope = getEnvelope('session.send');
    expect(envelope.v).toBe(2);
    expect(envelope.type).toBe('session.send');
    expect(envelope.tabId).toBe('tab-new');
    expect(envelope.sessionId).toBeNull();
    expect(envelope.requestId).toBeTruthy();

    const payload = envelope.payload;
    expect(payload.model).toBe('opencode/new-model');
    expect(payload.permissionMode).toBe('plan');
    expect(payload.reasoningEffort).toBe('high');
  });
});
