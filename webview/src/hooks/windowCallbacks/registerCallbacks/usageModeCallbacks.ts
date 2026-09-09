/**
 * usageModeCallbacks.ts
 *
 * Registers window bridge callbacks for usage statistics, permission modes, and
 * model updates: onUsageUpdate, onModeChanged, onModeReceived,
 * onModelChanged, onModelConfirmed, updateSendShortcut,
 * updateAutoOpenFileEnabled.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { PermissionMode, ReasoningEffort } from '../../../components/ChatInputBox/types';
import {
  has1MContextSuffix,
  normalizeClaudeModelId,
  strip1MContextSuffix,
} from '../../../components/ChatInputBox/types';
import { drainPendingSettings, startInitialSettingsRequest } from '../settingsBootstrap';
import { clampPermissionDialogTimeoutSeconds } from '../../../utils/permissionDialogTimeout';

export function registerUsageModeCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setPermissionMode,
    setCurrentProvider,
    setClaudePermissionMode,
    setOpenCodePermissionMode,
    setSelectedClaudeModel,
    setSelectedOpenCodeModel,
    setLongContextEnabled,
    setReasoningEffort,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setPermissionDialogTimeoutSeconds,
    currentProviderRef,
  } = options;

  window.onUsageUpdate = (json) => {
    try {
      const data = JSON.parse(json);
      if (typeof data.percentage === 'number') {
        const used =
          typeof data.usedTokens === 'number'
            ? data.usedTokens
            : typeof data.totalTokens === 'number'
              ? data.totalTokens
              : undefined;
        const max =
          typeof data.maxTokens === 'number'
            ? data.maxTokens
            : typeof data.limit === 'number'
              ? data.limit
              : undefined;

        if (used !== undefined && max !== undefined && used > max * 2) {
          console.warn(
            '[Frontend] Usage data may be incorrect: used=' + used + ', max=' + max,
          );
        }

        const safePercentage = Math.max(0, Math.min(100, data.percentage));
        setUsagePercentage(safePercentage);
        setUsageUsedTokens(used);
        setUsageMaxTokens(max);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse usage update:', error);
    }
  };

  if (typeof window.__pendingUsageUpdate === 'string') {
    const pending = window.__pendingUsageUpdate;
    delete window.__pendingUsageUpdate;
    window.onUsageUpdate(pending);
  }

  const updateMode = (mode?: PermissionMode, providerOverride?: string) => {
    const activeProvider = providerOverride || currentProviderRef.current;
    if (typeof mode === 'string' && mode.length > 0) {
      const nextMode: PermissionMode = mode;
      setPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      if (activeProvider === 'opencode') {
        setOpenCodePermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      } else {
        setClaudePermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      }
    }
  };

  window.onModeChanged = (mode) => updateMode(mode as PermissionMode);
  window.onModeReceived = (mode) => updateMode(mode as PermissionMode);

  window.onModelChanged = (modelId) => {
    const provider = currentProviderRef.current;
    if (provider === 'claude') {
      setSelectedClaudeModel(normalizeClaudeModelId(modelId));
    } else if (provider === 'opencode') {
      setSelectedOpenCodeModel(modelId);
    }
  };

  window.onModelConfirmed = (modelId, provider) => {
    if (provider === 'claude') {
      setSelectedClaudeModel(normalizeClaudeModelId(modelId));
    } else if (provider === 'opencode') {
      setSelectedOpenCodeModel(modelId);
    }
  };

  window.onSessionStateRestored = (json: string) => {
    try {
      const state = JSON.parse(json) as {
        model?: string;
        permissionMode?: PermissionMode;
        reasoningEffort?: ReasoningEffort;
      };
      // opencode-only：恢复的是 daemon session.get 的权威会话状态（跨会话
      // 加载时推送）。仅更新本地 UI 状态，绝不回发 set_model / set_mode，
      // 否则宿主 SessionState 与 daemon 值会互相覆盖。
      const model = typeof state.model === 'string' ? state.model.trim() : '';
      if (model) {
        setSelectedOpenCodeModel(model);
      }
      const mode = state.permissionMode;
      if (typeof mode === 'string' && mode.length > 0) {
        const nextMode = mode as PermissionMode;
        setOpenCodePermissionMode((prev) => (prev === nextMode ? prev : nextMode));
        setPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      }
      const reasoningValues: ReasoningEffort[] = ['low', 'medium', 'high', 'xhigh', 'max'];
      if (reasoningValues.includes(state.reasoningEffort as ReasoningEffort)) {
        setReasoningEffort(state.reasoningEffort as ReasoningEffort);
      }
    } catch (error) {
      console.error('[Frontend] Failed to apply restored session state:', error);
    }
  };

  window.applyBackendTabState = (json: string) => {
    try {
      const state = JSON.parse(json) as Record<string, unknown>;
      const rawProvider = state.provider;
      if (rawProvider !== 'claude' && rawProvider !== 'codex' && rawProvider !== 'opencode') {
        throw new Error('invalid provider');
      }
      // opencode-only fork: a persisted 'codex' tab falls back to opencode.
      const provider = rawProvider === 'codex' ? 'opencode' : rawProvider;

      // This is Java -> UI recovery state, not a user selection. Update the
      // synchronous ref and React state without emitting set_provider/set_model.
      currentProviderRef.current = provider;
      setCurrentProvider(provider);

      if (typeof state.model === 'string' && state.model.length > 0) {
        if (provider === 'claude') {
          setSelectedClaudeModel(normalizeClaudeModelId(strip1MContextSuffix(state.model)));
          setLongContextEnabled(has1MContextSuffix(state.model));
        } else {
          setSelectedOpenCodeModel(state.model);
        }
      }

      updateMode(state.permissionMode as PermissionMode | undefined, provider);

      const reasoningValues: ReasoningEffort[] = ['low', 'medium', 'high', 'xhigh', 'max'];
      if (reasoningValues.includes(state.reasoningEffort as ReasoningEffort)) {
        setReasoningEffort(state.reasoningEffort as ReasoningEffort);
      }
      window.__CCGUI_RECOVERY_STATE_APPLIED__ = true;
    } catch (error) {
      console.error('[Frontend] Failed to apply backend tab state:', error);
    }
  };

  if (typeof window.__pendingBackendTabState === 'string') {
    const pending = window.__pendingBackendTabState;
    delete window.__pendingBackendTabState;
    window.applyBackendTabState(pending);
  }

  window.updateSendShortcut = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      if (data.sendShortcut === 'enter' || data.sendShortcut === 'cmdEnter') {
        setSendShortcut(data.sendShortcut);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse send shortcut:', error);
    }
  };

  window.updateAutoOpenFileEnabled = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setAutoOpenFileEnabled(data.autoOpenFileEnabled ?? false);
    } catch (error) {
      console.error('[Frontend] Failed to parse auto open file enabled:', error);
    }
  };

  window.updatePermissionDialogTimeout = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setPermissionDialogTimeoutSeconds(clampPermissionDialogTimeoutSeconds(data.permissionDialogTimeoutSeconds));
    } catch (error) {
      const errorName = error instanceof Error ? error.name : 'UnknownError';
      console.error(`[Frontend] Failed to parse permission dialog timeout payload: ${errorName}`);
    }
  };

  // Drain any pending settings that arrived before callback registration
  drainPendingSettings();
  // Kick off initial settings requests
  startInitialSettingsRequest();
}
