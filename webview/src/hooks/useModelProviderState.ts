import { useCallback, useMemo, useRef, useState } from 'react';
import type { MutableRefObject } from 'react';
import type { TFunction as I18nTFunction } from 'i18next';
import { sendBridgeEvent } from '../utils/bridge';
import type {
  PermissionMode,
  ReasoningEffort,
} from '../components/ChatInputBox/types';
import { useOpenCodeProvider } from './providers/useOpenCodeProvider';
import { useUsageTracking } from './providers/useUsageTracking';
import { useProviderSettings } from './providers/useProviderSettings';
import { useModelStatePersistence } from './providers/useModelStatePersistence';

export type ViewMode = 'chat' | 'history' | 'settings';

export interface UseModelProviderStateOptions {
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  t: I18nTFunction;
  /** Current tab's sessionId, so set_mode is scoped to the right tab session. */
  currentSessionId?: string | null;
  /** Ref to the current tab's sessionId (always fresh). */
  currentSessionIdRef?: MutableRefObject<string | null>;
}

/**
 * Build the `set_model` payload.
 *
 * With a bound session the host receives `{ model, sessionId }` so it writes the
 * selection onto *that* OpencodeSession instead of "the window's current one" —
 * those drift apart as soon as the user switches tabs, and the change lands on
 * the wrong conversation while the UI reports success.
 *
 * An unbound tab still has a legacy bare-string form for backwards compatibility,
 * but callers must not use it: a tab without a session has no host target, so its
 * model travels with `send_message` instead.
 */
export function buildModelSelectionPayload(
  modelId: string,
  sessionId: string | null | undefined,
  options?: { silent?: boolean },
): string {
  if (!sessionId) return modelId;
  const payload: Record<string, unknown> = { model: modelId, sessionId };
  if (options?.silent) payload.silent = true;
  return JSON.stringify(payload);
}

/** Same scoping rule as {@link buildModelSelectionPayload}, for reasoning effort. */
export function buildReasoningEffortPayload(
  effort: string,
  sessionId: string | null | undefined,
): string {
  if (!sessionId) return effort;
  return JSON.stringify({ reasoningEffort: effort, sessionId });
}

/**
 * Resolve the active tab's bound session.
 *
 * When the host passes a ref it is authoritative: a tab that has just been
 * unbound / created deliberately has `null` there, and `null ?? currentSessionId`
 * would fall back to the *previous* tab's id while React state catches up. That
 * fallback is how a brand-new tab used to rewrite the outgoing session.
 */
function resolveBoundSessionId(
  currentSessionId: string | null | undefined,
  currentSessionIdRef: MutableRefObject<string | null> | undefined,
): string | null {
  if (currentSessionIdRef) return currentSessionIdRef.current;
  return currentSessionId ?? null;
}

/**
 * Orchestrates provider/model/permission state for OpenCode.
 */
export function useModelProviderState({ addToast, t, currentSessionId, currentSessionIdRef }: UseModelProviderStateOptions) {
  const [currentProvider, setCurrentProvider] = useState('opencode');
  const [permissionMode, setPermissionMode] = useState<PermissionMode>(
    () => (localStorage.getItem('opencode.permissionMode') as PermissionMode) || 'default',
  );
  // `default` (send no variant) rather than `medium`: most opencode models do not
  // offer `medium` at all, so defaulting to it picked a level that never applied.
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('default');

  const currentProviderRef = useRef(currentProvider);
  currentProviderRef.current = currentProvider;

  const openCode = useOpenCodeProvider();
  const { isSdkInstalled, isSdkStatusKnown, ...usage } = useUsageTracking();
  const settings = useProviderSettings({ addToast, t });

  const {
    selectedOpenCodeModel, setSelectedOpenCodeModel,
    openCodePermissionMode, setOpenCodePermissionMode,
  } = openCode;

  useModelStatePersistence({
    setCurrentProvider,
    setSelectedOpenCodeModel,
    setOpenCodePermissionMode,
    setPermissionMode,
    setReasoningEffort,
    currentProvider,
    selectedOpenCodeModel,
    openCodePermissionMode,
    reasoningEffort,
  });

  const selectedModel = selectedOpenCodeModel;
  const currentSdkInstalled = useMemo(
    () => isSdkInstalled(currentProvider),
    [isSdkInstalled, currentProvider],
  );

  const handleModeSelect = useCallback((mode: PermissionMode) => {
    setPermissionMode(mode);
    setOpenCodePermissionMode(mode);
    localStorage.setItem('opencode.permissionMode', mode);
    const sessionId = resolveBoundSessionId(currentSessionId, currentSessionIdRef);
    // Unbound tab: the mode has no host target yet. It travels with the first
    // send_message payload, so writing it to "the window's current session"
    // would rewrite the previously active tab instead.
    if (!sessionId) return;
    sendBridgeEvent('set_mode', JSON.stringify({ sessionId, mode }));
  }, [setOpenCodePermissionMode, currentSessionId, currentSessionIdRef]);

  /**
   * `silent` marks a value that a tab switch is restoring, not a user choice.
   *
   * The host treats a model change as "the user picked a new model": it clears
   * the session's context-usage and pushes an empty usage row. Replaying a tab's
   * own model on switch must not do that, or the usage ring zeroes out every
   * time two tabs with different models are alternated.
   */
  const handleModelSelect = useCallback((modelId: string, options?: { silent?: boolean }) => {
    setSelectedOpenCodeModel(modelId);
    const sessionId = resolveBoundSessionId(currentSessionId, currentSessionIdRef);
    // No session yet: keep it local; the model is sent with the first message.
    if (!sessionId) return;
    sendBridgeEvent('set_model', buildModelSelectionPayload(modelId, sessionId, options));
  }, [setSelectedOpenCodeModel, currentSessionId, currentSessionIdRef]);

  const handleProviderSelect = useCallback((providerId: string) => {
    setCurrentProvider(providerId);
    sendBridgeEvent('set_provider', providerId);
    setPermissionMode(openCodePermissionMode);
    const sessionId = resolveBoundSessionId(currentSessionId, currentSessionIdRef);
    if (!sessionId) return;
    sendBridgeEvent('set_mode', JSON.stringify({ sessionId, mode: openCodePermissionMode }));
    sendBridgeEvent('set_model', buildModelSelectionPayload(selectedOpenCodeModel, sessionId));
  }, [openCodePermissionMode, selectedOpenCodeModel, currentSessionId, currentSessionIdRef]);

  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    setReasoningEffort(effort);
    const sessionId = resolveBoundSessionId(currentSessionId, currentSessionIdRef);
    if (!sessionId) return;
    sendBridgeEvent('set_reasoning_effort', buildReasoningEffortPayload(effort, sessionId));
  }, [setReasoningEffort, currentSessionId, currentSessionIdRef]);

  const handleToggleThinking = useCallback((enabled: boolean) => {
    sendBridgeEvent('set_thinking_enabled', JSON.stringify({ enabled }));
    addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
  }, [addToast, t]);

  return {
    ...openCode,
    ...usage,
    ...settings,
    currentProvider, setCurrentProvider,
    permissionMode, setPermissionMode,
    selectedModel,
    reasoningEffort, setReasoningEffort,
    currentSdkInstalled,
    currentProviderRef,
    handleModeSelect,
    handleModelSelect,
    handleProviderSelect,
    handleToggleThinking,
    handleReasoningChange,
  };
}

