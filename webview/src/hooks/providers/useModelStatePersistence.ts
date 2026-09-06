import { useEffect } from 'react';
import { sendBridgeEvent } from '../../utils/bridge';
import { OPENCODE_DEFAULT_MODEL_ID } from '../../components/ChatInputBox/types';
import type { PermissionMode, ReasoningEffort } from '../../components/ChatInputBox/types';
import { isCliOnlyProvider } from './cliProviders';

const STORAGE_KEY = 'model-selection-state';
const REASONING_VALUES = ['low', 'medium', 'high', 'xhigh', 'max'] as const;

const isReasoningEffort = (value: unknown): value is ReasoningEffort =>
  typeof value === 'string' && (REASONING_VALUES as readonly string[]).includes(value);

export interface UseModelStatePersistenceOptions {
  // Cross-slice load setters (run once on mount)
  setCurrentProvider: (value: string) => void;
  setSelectedOpenCodeModel: (value: string) => void;
  setOpenCodePermissionMode: (value: PermissionMode) => void;
  setPermissionMode: (value: PermissionMode) => void;
  setReasoningEffort: (value: ReasoningEffort) => void;
  // Cross-slice save deps (re-saves on any change)
  currentProvider: string;
  selectedOpenCodeModel: string;
  openCodePermissionMode: PermissionMode;
  reasoningEffort: ReasoningEffort;
}

/**
 * Two effects for persisting model/permission state to localStorage:
 *  1. On mount: hydrate state from localStorage and sync the restored values
 *     to the backend (retrying until the JCEF bridge is ready).
 *  2. On change: re-save the snapshot to localStorage.
 */
export function useModelStatePersistence(options: UseModelStatePersistenceOptions) {
  const {
    setCurrentProvider,
    setSelectedOpenCodeModel,
    setOpenCodePermissionMode,
    setPermissionMode,
    setReasoningEffort,
    currentProvider,
    selectedOpenCodeModel,
    openCodePermissionMode,
    reasoningEffort,
  } = options;

  // Hydrate from localStorage and sync to backend (mount only).
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      // Per-tab restore: when the Java backend has loaded a saved session for
      // this specific tab, it injects __INITIAL_TAB_PROVIDER__ /
      // __INITIAL_TAB_MODEL__ into the HTML before React boots. Those values
      // win over the global localStorage snapshot.
      const initialTabProvider = typeof window.__INITIAL_TAB_PROVIDER__ === 'string'
        ? window.__INITIAL_TAB_PROVIDER__.trim()
        : '';
      const initialTabModel = typeof window.__INITIAL_TAB_MODEL__ === 'string'
        ? window.__INITIAL_TAB_MODEL__.trim()
        : '';
      const hasBackendProvider = initialTabProvider === 'opencode'
        || isCliOnlyProvider(initialTabProvider);
      const hasBackendModel = initialTabModel.length > 0;

      let restoredProvider = 'opencode';
      let restoredOpenCodeModel = OPENCODE_DEFAULT_MODEL_ID;
      let restoredOpenCodePermissionMode: PermissionMode = 'default';

      // CLI catalogs are dynamic (backend-reported OpenCode models), so any
      // non-empty saved id is accepted.
      const applyOpenCodeModel = (modelId: unknown) => {
        if (typeof modelId === 'string' && modelId.trim().length > 0) {
          restoredOpenCodeModel = modelId;
          setSelectedOpenCodeModel(modelId);
        }
      };

      if (saved) {
        const state = JSON.parse(saved);

        const providerCandidate = hasBackendProvider ? initialTabProvider : state.provider;
        if (providerCandidate === 'opencode' || isCliOnlyProvider(providerCandidate)) {
          restoredProvider = providerCandidate;
          setCurrentProvider(providerCandidate);
        }

        if (typeof state.openCodePermissionMode === 'string' && state.openCodePermissionMode) {
          restoredOpenCodePermissionMode = state.openCodePermissionMode;
        }

        if (isReasoningEffort(state.reasoningEffort)) {
          setReasoningEffort(state.reasoningEffort);
        }

        const openCodeModelCandidate = hasBackendModel ? initialTabModel : state.openCodeModel;
        applyOpenCodeModel(openCodeModelCandidate);
      } else if (hasBackendProvider) {
        restoredProvider = initialTabProvider;
        setCurrentProvider(initialTabProvider);
        if (hasBackendModel) {
          applyOpenCodeModel(initialTabModel);
        }
      }

      setOpenCodePermissionMode(restoredOpenCodePermissionMode);
      setPermissionMode(restoredOpenCodePermissionMode);

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
          sendBridgeEvent('set_model', restoredOpenCodeModel);
          // Do NOT push the permission mode to Java on boot. Java is the source
          // of truth for the mode and the webview seeds its own mode FROM Java
          // via get_mode → onModeReceived.
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
      // snapshot shared by every tab.
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
          openCodeModel: selectedOpenCodeModel,
          openCodePermissionMode,
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
  }, [currentProvider, selectedOpenCodeModel, openCodePermissionMode, reasoningEffort]);
}
