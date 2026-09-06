import { useCallback, useMemo, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { sendBridgeEvent } from '../utils/bridge';
import type { PermissionMode, ReasoningEffort } from '../components/ChatInputBox/types';
import { useOpenCodeProvider } from './providers/useOpenCodeProvider';
import { normalizeCliPermissionMode } from './providers/cliProviders';
import { useUsageTracking } from './providers/useUsageTracking';
import { useProviderSettings } from './providers/useProviderSettings';
import { useModelStatePersistence } from './providers/useModelStatePersistence';

export type ViewMode = 'chat' | 'history' | 'settings';

export interface UseModelProviderStateOptions {
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  t: TFunction;
}

/**
 * Orchestrates model/permission state for the OpenCode-only build. The
 * provider is fixed to `opencode`; composes the opencode model slice, usage
 * tracking, provider settings and persistence.
 *
 * `currentProviderRef` is exposed for window callbacks registered with stable
 * identity that must read the current provider when fired by the JCEF bridge.
 */
export function useModelProviderState({ addToast, t }: UseModelProviderStateOptions) {
  // ── Cross-slice state owned by the orchestrator (fixed provider) ──
  const [currentProvider, setCurrentProvider] = useState('opencode');
  const [permissionMode, setPermissionMode] = useState<PermissionMode>('default');
  // Reasoning effort maps 1:1 to the opencode model variant.
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('high');

  // External-facing ref so window callbacks can read the latest provider
  // without re-binding. Render-time assignment avoids the useRef + useEffect
  // mirror anti-pattern (rule 5.15).
  const currentProviderRef = useRef(currentProvider);
  currentProviderRef.current = currentProvider;

  // ── Provider-specific sub-hooks ──
  const openCode = useOpenCodeProvider();
  const { isSdkInstalled, isSdkStatusKnown, sdkStatus, ...usage } = useUsageTracking();
  const settings = useProviderSettings({ addToast, t });

  const {
    selectedOpenCodeModel, setSelectedOpenCodeModel,
    openCodePermissionMode, setOpenCodePermissionMode,
  } = openCode;

  // ── Persistence: load on mount + save on change ──
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

  // ── Computed values ──
  const selectedModel = selectedOpenCodeModel;
  const currentSdkInstalled = useMemo(
    () => isSdkInstalled(currentProvider),
    [isSdkInstalled, currentProvider],
  );
  const currentSdkStatusError = useMemo(
    () => usage.sdkStatusError !== null && !isSdkStatusKnown(currentProvider)
      ? usage.sdkStatusError
      : null,
    [currentProvider, isSdkStatusKnown, usage.sdkStatusError],
  );

  // ── Cross-provider handlers (single opencode path) ──
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    const cliMode = normalizeCliPermissionMode(mode, currentProvider);
    setPermissionMode(cliMode);
    setOpenCodePermissionMode(cliMode);
    sendBridgeEvent('set_mode', cliMode);
  }, [currentProvider, setOpenCodePermissionMode]);

  const handleModelSelect = useCallback((modelId: string) => {
    setSelectedOpenCodeModel(modelId);
    sendBridgeEvent('set_model', modelId);
  }, [setSelectedOpenCodeModel]);

  const handleProviderSelect = useCallback((providerId: string) => {
    // OpenCode-only build: the provider cannot be changed from the UI. Echo the
    // fixed provider so backend state stays consistent.
    void providerId;
    setCurrentProvider('opencode');
    sendBridgeEvent('set_provider', 'opencode');
    sendBridgeEvent('set_model', selectedOpenCodeModel);
    sendBridgeEvent('set_mode', openCodePermissionMode);
  }, [selectedOpenCodeModel, openCodePermissionMode]);

  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    setReasoningEffort(effort);
    sendBridgeEvent('set_reasoning_effort', effort);
  }, []);

  return {
    ...openCode,
    ...usage,
    ...settings,
    sdkStatus,
    sdkStatusError: currentSdkStatusError,
    currentProvider, setCurrentProvider,
    permissionMode, setPermissionMode,
    setReasoningEffort,
    selectedModel,
    currentSdkInstalled,
    currentProviderRef,
    reasoningEffort,
    handleModeSelect,
    handleModelSelect,
    handleProviderSelect,
    handleReasoningChange,
  };
}
