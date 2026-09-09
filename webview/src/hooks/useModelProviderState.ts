import { useCallback, useMemo, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { sendBridgeEvent } from '../utils/bridge';
import {
  apply1MContextSuffix,
  normalizeClaudeModelId,
  strip1MContextSuffix,
} from '../components/ChatInputBox/types';
import type {
  PermissionMode,
  ReasoningEffort,
} from '../components/ChatInputBox/types';
import { isSpecialProviderId } from '../types/provider';
import { useClaudeProvider } from './providers/useClaudeProvider';
import { useOpenCodeProvider } from './providers/useOpenCodeProvider';
import { isCliOnlyProvider, normalizeCliPermissionMode } from './providers/cliProviders';
import { useUsageTracking } from './providers/useUsageTracking';
import { useProviderSettings } from './providers/useProviderSettings';
import { useModelStatePersistence } from './providers/useModelStatePersistence';

export type ViewMode = 'chat' | 'history' | 'settings';

export interface UseModelProviderStateOptions {
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  t: TFunction;
}

/**
 * Orchestrates provider/model/permission state. Composes four single-purpose
 * sub-hooks (Claude / Codex / usage tracking / provider settings) plus a
 * persistence hook, then wires the cross-slice state (currentProvider +
 * permissionMode) and the cross-provider handlers (mode/model/provider switch,
 * long-context toggle, always-thinking toggle).
 *
 * The flat return shape is preserved as the public API: callers (App,
 * ChatScreen, AppDialogs, useMessageSender) destructure individual fields.
 *
 * `currentProviderRef` is exposed for window callbacks registered with stable
 * identity that must read the current provider when fired by the JCEF bridge.
 * The ref is updated via render-time assignment (no useEffect mirror).
 */
export function useModelProviderState({ addToast, t }: UseModelProviderStateOptions) {
  // ── Cross-slice state owned by the orchestrator ──
  const [currentProvider, setCurrentProvider] = useState('opencode');
  const [permissionMode, setPermissionMode] = useState<PermissionMode>(
    () => (localStorage.getItem('opencode.permissionMode') as PermissionMode) || 'default',
  );

  // reasoningEffort is a cross-provider concept (shared by claude / opencode).
  // It used to live in the now-removed codex stub; re-home it here.
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('medium');

  // External-facing ref so window callbacks can read the latest provider
  // without re-binding. Render-time assignment avoids the useRef + useEffect
  // mirror anti-pattern (rule 5.15).
  const currentProviderRef = useRef(currentProvider);
  currentProviderRef.current = currentProvider;

  // ── Provider-specific sub-hooks ──
  // NOTE: useClaudeProvider / useCodexProvider keep their historical names, but
  // the state slots they own (long context, reasoning effort, fast mode and the
  // model id echoed back by the Java bridge) are consumed by opencode at
  // runtime — see usageModeCallbacks.ts. Only grok / kimi / pi were removed.
  const claude = useClaudeProvider();
  const openCode = useOpenCodeProvider();
  const { isSdkInstalled, isSdkStatusKnown, ...usage } = useUsageTracking();
  const settings = useProviderSettings({ addToast, t });

  const {
    selectedClaudeModel, setSelectedClaudeModel,
    claudePermissionMode, setClaudePermissionMode,
    longContextEnabled, setLongContextEnabled,
    setClaudeSettingsAlwaysThinkingEnabled,
  } = claude;
  const {
    selectedOpenCodeModel, setSelectedOpenCodeModel,
    openCodePermissionMode, setOpenCodePermissionMode,
  } = openCode;

  // ── Persistence: load on mount + save on change ──
  useModelStatePersistence({
    setCurrentProvider,
    setSelectedClaudeModel,
    setClaudePermissionMode,
    setSelectedOpenCodeModel,
    setOpenCodePermissionMode,
    setPermissionMode,
    setLongContextEnabled,
    setReasoningEffort,
    currentProvider,
    selectedClaudeModel,
    claudePermissionMode,
    selectedOpenCodeModel,
    openCodePermissionMode,
    longContextEnabled,
    reasoningEffort,
  });

  // ── Computed values ──
  const selectedModel = currentProvider === 'opencode'
    ? selectedOpenCodeModel
    : selectedClaudeModel;
  const currentSdkInstalled = useMemo(
    () => isSdkInstalled(currentProvider),
    [isSdkInstalled, currentProvider],
  );

  // ── Cross-provider handlers ──
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    if (currentProvider === 'opencode') {
      // OpenCode 支持原生 plan agent，模式原样透传。
      setPermissionMode(mode);
      setOpenCodePermissionMode(mode);
      sendBridgeEvent('set_mode', mode);
      localStorage.setItem('opencode.permissionMode', mode);
      return;
    }
    if (isCliOnlyProvider(currentProvider)) {
      // opencode is the only remaining CLI-only provider.
      const cliMode = normalizeCliPermissionMode(mode);
      setPermissionMode(cliMode);
      setOpenCodePermissionMode(cliMode);
      sendBridgeEvent('set_mode', cliMode);
      return;
    }
    setPermissionMode(mode);
    setClaudePermissionMode(mode);
    sendBridgeEvent('set_mode', mode);
  }, [
    currentProvider,
    setClaudePermissionMode,
    setOpenCodePermissionMode,
  ]);

  const handleModelSelect = useCallback((modelId: string) => {
    if (currentProvider === 'claude') {
      const strippedModelId = strip1MContextSuffix(modelId);
      const normalizedModelId = normalizeClaudeModelId(strippedModelId);
      setSelectedClaudeModel(normalizedModelId);
      sendBridgeEvent('set_model', apply1MContextSuffix(normalizedModelId, longContextEnabled));
    } else if (currentProvider === 'opencode') {
      setSelectedOpenCodeModel(modelId);
      sendBridgeEvent('set_model', modelId);
    }
  }, [
    currentProvider,
    longContextEnabled,
    setSelectedClaudeModel,
    setSelectedOpenCodeModel,
  ]);

  const handleProviderSelect = useCallback((providerId: string) => {
    setCurrentProvider(providerId);
    sendBridgeEvent('set_provider', providerId);

    let modeToSet: PermissionMode = claudePermissionMode;
    if (providerId === 'opencode') {
      // OpenCode 支持原生 plan agent，不做 CLI 的 plan 屏蔽。
      modeToSet = openCodePermissionMode;
    }
    setPermissionMode(modeToSet);
    sendBridgeEvent('set_mode', modeToSet);

    let newModel = apply1MContextSuffix(selectedClaudeModel, longContextEnabled);
    if (providerId === 'opencode') newModel = selectedOpenCodeModel;
    sendBridgeEvent('set_model', newModel);
  }, [
    claudePermissionMode,
    openCodePermissionMode,
    selectedClaudeModel,
    selectedOpenCodeModel,
    longContextEnabled,
  ]);

  const handleLongContextChange = useCallback((enabled: boolean) => {
    setLongContextEnabled(enabled);
    if (currentProvider === 'claude') {
      sendBridgeEvent('set_model', apply1MContextSuffix(selectedClaudeModel, enabled));
    }
  }, [currentProvider, selectedClaudeModel, setLongContextEnabled]);

  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    setReasoningEffort(effort);
    sendBridgeEvent('set_reasoning_effort', effort);
  }, [setReasoningEffort]);

  const handleToggleThinking = useCallback((enabled: boolean) => {
    const config = settings.activeProviderConfig;
    const isSpecialProvider = isSpecialProviderId(config?.id || '');

    setClaudeSettingsAlwaysThinkingEnabled(enabled);

    if (!config || isSpecialProvider) {
      settings.setActiveProviderConfig(prev => prev ? {
        ...prev,
        settingsConfig: {
          ...prev.settingsConfig,
          alwaysThinkingEnabled: enabled,
        },
      } : prev);
      sendBridgeEvent('set_thinking_enabled', JSON.stringify({ enabled }));
      addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
      return;
    }

    settings.setActiveProviderConfig(prev => prev ? {
      ...prev,
      settingsConfig: {
        ...prev.settingsConfig,
        alwaysThinkingEnabled: enabled,
      },
    } : null);

    sendBridgeEvent('update_provider', JSON.stringify({
      id: config.id,
      updates: {
        settingsConfig: {
          ...(config.settingsConfig || {}),
          alwaysThinkingEnabled: enabled,
        },
      },
    }));
    addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
  }, [settings, setClaudeSettingsAlwaysThinkingEnabled, addToast, t]);

  return {
    ...claude,
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
    handleLongContextChange,
    handleToggleThinking,
    handleReasoningChange,
  };
}
