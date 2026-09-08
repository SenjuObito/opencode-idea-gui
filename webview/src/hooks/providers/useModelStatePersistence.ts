import { useEffect } from 'react';
import { sendBridgeEvent } from '../../utils/bridge';
import {
  CODEX_MODELS,
  OPENCODE_DEFAULT_MODEL_ID,
} from '../../components/ChatInputBox/types';
import type {
  CodexFastMode,
  PermissionMode,
  ReasoningEffort,
} from '../../components/ChatInputBox/types';
import { isCliOnlyProvider } from './cliProviders';

const STORAGE_KEY = 'model-selection-state';
const REASONING_VALUES = ['low', 'medium', 'high', 'xhigh', 'max'] as const;

const isReasoningEffort = (value: unknown): value is ReasoningEffort =>
  typeof value === 'string' && (REASONING_VALUES as readonly string[]).includes(value);

export interface UseModelStatePersistenceOptions {
  // Cross-slice load setters (run once on mount)
  setCurrentProvider: (value: string) => void;
  setSelectedClaudeModel: (value: string) => void;
  setSelectedCodexModel: (value: string) => void;
  setClaudePermissionMode: (value: PermissionMode) => void;
  setCodexPermissionMode: (value: PermissionMode) => void;
  setSelectedOpenCodeModel: (value: string) => void;
  setOpenCodePermissionMode: (value: PermissionMode) => void;
  setPermissionMode: (value: PermissionMode) => void;
  setLongContextEnabled: (value: boolean) => void;
  setReasoningEffort: (value: ReasoningEffort) => void;
  setCodexFastMode?: (value: CodexFastMode) => void;
  // Cross-slice save deps (re-saves on any change)
  currentProvider: string;
  selectedClaudeModel: string;
  selectedCodexModel: string;
  claudePermissionMode: PermissionMode;
  codexPermissionMode: PermissionMode;
  selectedOpenCodeModel: string;
  openCodePermissionMode: PermissionMode;
  longContextEnabled: boolean;
  reasoningEffort: ReasoningEffort;
  codexFastMode?: CodexFastMode;
}

/**
 * Two effects for persisting cross-slice provider/model state to localStorage:
 *  1. On mount: hydrate state from localStorage and sync the restored values
 *     to the backend (retrying until the JCEF bridge is ready).
 *  2. On change: re-save the snapshot to localStorage.
 *
 * Save uses `JSON.stringify` of the persisted keys; load applies
 * defensive validation (custom models lookup, permission mode allowlist,
 * reasoning effort allowlist) before invoking the slice setters.
 */
export function useModelStatePersistence(options: UseModelStatePersistenceOptions) {
  const {
    setCurrentProvider,
    setSelectedCodexModel,
    setCodexPermissionMode,
    setSelectedOpenCodeModel,
    setOpenCodePermissionMode,
    setPermissionMode,
    setLongContextEnabled,
    setReasoningEffort,
    currentProvider,
    selectedClaudeModel,
    selectedCodexModel,
    claudePermissionMode,
    codexPermissionMode,
    selectedOpenCodeModel,
    openCodePermissionMode,
    longContextEnabled,
    reasoningEffort,
  } = options;

  // Hydrate from localStorage and sync to backend (mount only).
  // Setters are stable; deps left empty to ensure single execution.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      // Per-tab restore (issue #1353): when the Java backend has loaded a saved
      // session for this specific tab, it injects __INITIAL_TAB_PROVIDER__ /
      // __INITIAL_TAB_MODEL__ into the HTML before React boots. Those values
      // win over the global localStorage snapshot, which is shared across every
      // tab in the JCEF process and would otherwise cause every CC tab on
      // restart to be set to whichever provider was last saved by ANY tab.
      const initialTabProvider = typeof window.__INITIAL_TAB_PROVIDER__ === 'string'
        ? window.__INITIAL_TAB_PROVIDER__.trim()
        : '';
      const initialTabModel = typeof window.__INITIAL_TAB_MODEL__ === 'string'
        ? window.__INITIAL_TAB_MODEL__.trim()
        : '';
      // OpenCode-only build: 'claude' is a legacy multi-engine value. The
      // backend session may still carry it (older persisted tab state); treat
      // it as "no backend preference" so the tab falls back to opencode.
      const hasBackendProvider = initialTabProvider === 'codex'
        || isCliOnlyProvider(initialTabProvider);
      const hasBackendModel = initialTabModel.length > 0;

      let restoredProvider = 'opencode';
      let restoredCodexModel = CODEX_MODELS[0].id;
      let restoredCodexPermissionMode: PermissionMode = 'default';
      let restoredOpenCodeModel = OPENCODE_DEFAULT_MODEL_ID;
      let restoredOpenCodePermissionMode: PermissionMode = 'build';

      // Model validation helpers — close over the restored* lets so both
      // branches (saved localStorage / fresh backend-only) share the same logic.
      const applyCodexModel = (modelId: string) => {
        // Codex catalogs are dynamic (config.toml `model` + model_catalog_json),
        // so any non-empty saved id is accepted — same policy as CLI providers.
        // A stale id is corrected by the catalog auto-select once the fetch lands.
        if (typeof modelId === 'string' && modelId.trim().length > 0) {
          restoredCodexModel = modelId;
          setSelectedCodexModel(modelId);
        }
      };
      // CLI catalogs are dynamic (opencode models are reported by the backend),
      // so any non-empty saved id is accepted.
      const makeCliModelApplier = (apply: (id: string) => void) => (modelId: unknown) => {
        if (typeof modelId === 'string' && modelId.trim().length > 0) {
          apply(modelId);
        }
      };
      const applyOpenCodeModel = makeCliModelApplier((id) => {
        restoredOpenCodeModel = id;
        setSelectedOpenCodeModel(id);
      });

      if (saved) {
        const state = JSON.parse(saved);

        // Backend-supplied provider wins. We still fall through the rest of the
        // hydration so non-provider preferences (permission mode, reasoning
        // effort, codex fast mode, …) are restored from localStorage.
        // OpenCode-only build: a stored 'claude' (legacy multi-engine snapshot)
        // is NOT a valid provider here — fall back to opencode so the picker
        // renders the opencode catalog instead of the claude built-ins.
        const providerCandidate = hasBackendProvider ? initialTabProvider : state.provider;
        if (providerCandidate === 'codex' || isCliOnlyProvider(providerCandidate)) {
          restoredProvider = providerCandidate;
          setCurrentProvider(providerCandidate);
        } else {
          restoredProvider = 'opencode';
          setCurrentProvider('opencode');
        }

        if (typeof state.codexPermissionMode === 'string' && state.codexPermissionMode.length > 0) {
          restoredCodexPermissionMode = state.codexPermissionMode === 'plan'
            ? 'default'
            : state.codexPermissionMode;
        }
        if (typeof state.openCodePermissionMode === 'string' && state.openCodePermissionMode.length > 0) {
          // Backward compatibility: old 'default' maps to opencode 'build' agent.
          restoredOpenCodePermissionMode = state.openCodePermissionMode === 'default'
            ? 'build'
            : state.openCodePermissionMode;
        }

        if (typeof state.longContextEnabled === 'boolean') {
          setLongContextEnabled(state.longContextEnabled);
        }

        if (isReasoningEffort(state.reasoningEffort)) {
          setReasoningEffort(state.reasoningEffort);
        }

        // A backend model may only override when it came from a valid backend
        // provider; a legacy claude tab state pairs a claude model with the
        // opencode fallback provider, and mixing them would sync a claude id
        // into the opencode session.
        const backendModelApplies = hasBackendProvider && hasBackendModel;
        const codexModelCandidate = backendModelApplies && restoredProvider === 'codex'
          ? initialTabModel
          : state.codexModel;
        applyCodexModel(codexModelCandidate);

        const openCodeModelCandidate = backendModelApplies && restoredProvider === 'opencode'
          ? initialTabModel
          : state.openCodeModel;
        applyOpenCodeModel(openCodeModelCandidate);
      } else if (hasBackendProvider) {
        // No localStorage yet (fresh user) but backend supplied a provider:
        // honor it so the tab starts with the right provider.
        restoredProvider = initialTabProvider;
        setCurrentProvider(initialTabProvider);
        if (hasBackendModel) {
          if (initialTabProvider === 'codex') applyCodexModel(initialTabModel);
          else if (initialTabProvider === 'opencode') applyOpenCodeModel(initialTabModel);
        }
      }

      const initialPermissionMode: PermissionMode = restoredProvider === 'codex'
        ? restoredCodexPermissionMode
        : restoredOpenCodePermissionMode;
      setCodexPermissionMode(restoredCodexPermissionMode);
      setOpenCodePermissionMode(restoredOpenCodePermissionMode);
      setPermissionMode(initialPermissionMode);

      let syncRetryCount = 0;
      const MAX_SYNC_RETRIES = 30;

      const syncToBackend = () => {
        if (window.sendToJava) {
          // Native watchdog reload reuses the original HTML snapshot. Java
          // pushes the current Session state after frontend_ready; echoing the
          // stale boot snapshot would route the existing transcript incorrectly.
          if (window.__CCGUI_RECOVERY_RELOAD__ === true) {
            return;
          }
          sendBridgeEvent('set_provider', restoredProvider);
          // OpenCode-only build: restoredProvider is always opencode or codex.
          const modelToSync = restoredProvider === 'codex'
            ? restoredCodexModel
            : restoredOpenCodeModel;
          sendBridgeEvent('set_model', modelToSync);
          // Do NOT push the permission mode to Java on boot. Java is the source
          // of truth for the mode (persisted app-level in PropertiesComponent,
          // which survives a plugin reinstall) and the webview seeds its own mode
          // FROM Java via get_mode → onModeReceived. Our localStorage copy is
          // wiped on reinstall, so pushing it here would clobber the surviving
          // Java value with 'default' — the reported "reinstall forgets Auto" bug.
          // The mode is only sent to Java on an explicit user switch
          // (handleModeSelect → set_mode).
        } else {
          syncRetryCount++;
          if (syncRetryCount < MAX_SYNC_RETRIES) {
            setTimeout(syncToBackend, 100);
          }
        }
      };
      setTimeout(syncToBackend, 200);
    } catch {
      // Failed to load model selection state — fall back to defaults already
      // set by individual slice hooks.
    }
  }, []);

  // Persist snapshot whenever any of the persisted keys change.
  useEffect(() => {
    let retryTimer: number | undefined;
    let retryCount = 0;

    const persistWhenPageContextIsReady = () => {
      const pageContextPending = window.__CCGUI_PAGE_CONTEXT_READY__ !== true;
      const recoveryStatePending = window.__CCGUI_RECOVERY_RELOAD__ === true
        && window.__CCGUI_RECOVERY_STATE_APPLIED__ !== true;

      // React may mount before onLoadEnd/fallback establishes the runtime page
      // context. Never publish provisional HTML/default state to the localStorage
      // snapshot shared by every tab. Keep the same fast-then-slow retry policy as
      // bridge startup so delayed remote JCEF initialization can still settle.
      if (pageContextPending || recoveryStatePending) {
        retryCount += 1;
        retryTimer = window.setTimeout(
          persistWhenPageContextIsReady,
          retryCount < 50 ? 100 : 1000,
        );
        return;
      }

      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify({
          provider: currentProvider,
          claudeModel: selectedClaudeModel,
          codexModel: selectedCodexModel,
          claudePermissionMode,
          codexPermissionMode,
          openCodeModel: selectedOpenCodeModel,
          openCodePermissionMode,
          longContextEnabled,
          reasoningEffort,
        }));
      } catch {
        // Failed to save model selection state — non-fatal.
      }
    };

    persistWhenPageContextIsReady();
    return () => {
      if (retryTimer !== undefined) {
        window.clearTimeout(retryTimer);
      }
    };
  }, [
    currentProvider,
    selectedClaudeModel,
    selectedCodexModel,
    claudePermissionMode,
    codexPermissionMode,
    selectedOpenCodeModel,
    openCodePermissionMode,
    longContextEnabled,
    reasoningEffort,
  ]);
}
