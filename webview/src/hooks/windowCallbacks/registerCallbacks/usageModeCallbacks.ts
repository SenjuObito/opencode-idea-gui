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
import { drainPendingSettings, startInitialSettingsRequest } from '../settingsBootstrap';
import { clampPermissionDialogTimeoutSeconds } from '../../../utils/permissionDialogTimeout';
import { cardDebugLog } from '../../../utils/bridge';
import { resolveSessionIdWithLegacyFallback } from '../../../utils/sessionIdentity';

export function registerUsageModeCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setPermissionMode,
    setCurrentProvider,
    setOpenCodePermissionMode,
    setSelectedOpenCodeModel,
    setReasoningEffort,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setPermissionDialogTimeoutSeconds,
    currentProviderRef,
  } = options;

  window.onUsageUpdate = (arg0: string, arg1?: string) => {
    // Usage is per-session state. An update without a session id is
    // unattributable: guessing "the active tab" is exactly what copied the
    // previous session's tokens onto a freshly created one. resolveSessionId
    // counts and drops it instead.
    const { sessionId: targetSessionId, payload } = resolveSessionIdWithLegacyFallback(
      arg0,
      arg1,
      'usage',
    );
    if (!targetSessionId) {
      return;
    }

    const jsonStr = typeof payload === 'string' ? payload : JSON.stringify(payload);

    try {
      const data = JSON.parse(jsonStr);
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
          cardDebugLog(
            '[Frontend] Usage data may be incorrect: used=' + used + ', max=' + max,
          );
        }

        const safePercentage = Math.max(0, Math.min(100, data.percentage));

        // If targetSessionId belongs to a background tab, update that tab's
        // data directly. Never touch the active view from a background update.
        if (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId)) {
          window.__sessionTabsStore.updateBackgroundTab(
            (t) => t.sessionId === targetSessionId,
            (tab) => {
              tab.usagePercentage = safePercentage;
              tab.usageUsedTokens = used;
              tab.usageMaxTokens = max;
            }
          );
          return;
        }

        // Active session (or the tab store has not mounted yet): update global
        // UI state AND persist into that session's tab so switching back
        // restores its own usage.
        setUsagePercentage(safePercentage);
        setUsageUsedTokens(used);
        setUsageMaxTokens(max);

        if (window.__sessionTabsStore) {
          window.__sessionTabsStore.updateBackgroundTab(
            (t) => t.sessionId === targetSessionId,
            (tab) => {
              tab.usagePercentage = safePercentage;
              tab.usageUsedTokens = used;
              tab.usageMaxTokens = max;
            }
          );
        }
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse usage update:', error);
    }
  };

  if (window.__pendingUsageUpdate) {
    const pending = window.__pendingUsageUpdate as { arg0: string; arg1?: string } | string;
    delete window.__pendingUsageUpdate;
    if (typeof pending === 'object' && pending !== null && 'arg0' in pending) {
      window.onUsageUpdate(pending.arg0, pending.arg1);
    } else if (typeof pending === 'string') {
      window.onUsageUpdate(pending);
    }
  }

  const updateMode = (mode?: PermissionMode) => {
    if (typeof mode === 'string' && mode.length > 0) {
      const nextMode: PermissionMode = mode;
      setPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      setOpenCodePermissionMode((prev) => (prev === nextMode ? prev : nextMode));
    }
  };

  window.onModeChanged = (mode) => updateMode(mode as PermissionMode);
  window.onModeReceived = (mode) => updateMode(mode as PermissionMode);

  window.onModelChanged = (modelId) => {
    setSelectedOpenCodeModel(modelId);
  };

  window.onModelConfirmed = (modelId) => {
    setSelectedOpenCodeModel(modelId);
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
      // Normalize legacy 'codex' provider to 'opencode' for backward compatibility
      const rawProvider = typeof state.provider === 'string' && state.provider.length > 0
        ? state.provider
        : 'opencode';
      const provider = rawProvider === 'codex' ? 'opencode' : rawProvider;

      currentProviderRef.current = provider;
      setCurrentProvider(provider);

      if (typeof state.model === 'string' && state.model.length > 0) {
        const model = state.model;
        // opencode-only：长上下文开关未接入，[1m] 只是恢复模型时的展示后缀。
        const baseModel = model.endsWith('[1m]') ? model.slice(0, -4) : model;
        setSelectedOpenCodeModel(baseModel);
      }

      updateMode(state.permissionMode as PermissionMode | undefined);

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
