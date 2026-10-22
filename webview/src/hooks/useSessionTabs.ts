import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage } from '../types';
import type { ContextInfo } from './useWindowCallbacks';
import type { TabScrollState } from './useScrollBehavior';
import type { SessionTabData } from '../components/ChatHeader/types';
import type { TabType } from '../components/StatusPanel/types';
import { TabStore, createTabId } from '../store/tabStore';
import { TabRuntimeStore } from '../store/tabRuntimeStore';
import { TabDraftStore } from '../store/tabDraftStore';
import type { ModelInfo, PermissionMode, ReasoningEffort } from '../components/ChatInputBox/types';
import { getFirstPreferredModelId, resolveSessionModel } from '../components/ChatInputBox/modelSelectUtils';
import { sendBridgeEvent, cardDebugLog } from '../utils/bridge';
import { clearSessionSequenceBarrier } from './windowCallbacks/sequenceBarrier';
import { releaseSessionTransition } from './windowCallbacks/sessionTransition';
import {
  DEFAULT_STREAMING_RUNTIME,
  captureStreamingRuntime,
  clearStreamingRuntime,
  restoreStreamingRuntime,
  type StreamingRuntimeRefs,
} from '../utils/streamingRuntime';

interface UseSessionTabsOptions {
  currentSessionId: string | null;
  setCurrentSessionId?: (sessionId: string | null) => void;
  sessionTitle: string;
  setCustomSessionTitle: (title: string | null) => void;
  updateHistoryTitle: (sessionId: string, newTitle: string) => void;
  messages: ClaudeMessage[];
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  loading: boolean;
  setLoading?: (loading: boolean) => void;
  streamingActive: boolean;
  setStreamingActive?: (active: boolean) => void;
  isThinking?: boolean;
  setIsThinking?: (thinking: boolean) => void;
  status?: string;
  setStatus?: (status: string) => void;
  sessionLoading?: boolean;
  setSessionLoading?: (loading: boolean) => void;
  revertBoundaryId?: string | null;
  setRevertBoundaryId?: (id: string | null) => void;
  isCompacting?: boolean;
  setIsCompacting?: (compacting: boolean) => void;
  compactingStartTime?: number | null;
  setCompactingStartTime?: (time: number | null) => void;
  taskEvents?: Record<string, any>;
  setTaskEvents?: (events: Record<string, any>) => void;
  sseTodos?: any[] | null;
  setSseTodos?: (todos: any[] | null) => void;
  subagentHistories?: Record<string, any>;
  setSubagentHistories?: (histories: Record<string, any>) => void;
  filteredFileChanges?: any[];
  setFilteredFileChanges?: (changes: any[]) => void;
  permissionMode?: PermissionMode;
  setPermissionMode?: (mode: PermissionMode) => void;
  selectedModel?: string;
  /**
   * `silent` marks a value restored by a tab switch rather than chosen by the
   * user, so the host can skip the "model changed" side effects (usage reset).
   */
  setSelectedModel?: (model: string, options?: { silent?: boolean }) => void;
  reasoningEffort?: ReasoningEffort;
  setReasoningEffort?: (effort: ReasoningEffort) => void;
  contextInfo?: ContextInfo | null;
  setContextInfo?: (info: ContextInfo | null) => void;
  availableModels?: ModelInfo[];
  currentProvider?: string;
  usagePercentage?: number;
  setUsagePercentage?: (percent: number) => void;
  usageUsedTokens?: number;
  setUsageUsedTokens?: (tokens: number | undefined) => void;
  usageMaxTokens?: number;
  setUsageMaxTokens?: (tokens: number | undefined) => void;
  loadingStartTime?: number | null;
  setLoadingStartTime?: (time: number | null) => void;
  isShared: boolean;
  setCurrentView?: (view: 'chat' | 'history' | 'settings') => void;
  chatInputRef?: React.RefObject<any>;
  defaultAutoOpenFileEnabled?: boolean;
  forceCreateNewSession: () => void;
  loadHistorySession: (sessionId: string) => void;
  currentSessionIdRef?: React.RefObject<string | null>;
  /**
   * The live streaming refs. Supplying them enables per-tab runtime exchange:
   * leaving a session archives the accumulator / turnId / index, entering one
   * loads it back. Without them a session that keeps running in the background
   * would leak its text into whichever session is activated next.
   */
  streamingRuntimeRefs?: StreamingRuntimeRefs;
  /**
   * Scroll isolation hooks. `captureScrollState` snapshots the outgoing tab's
   * reader position before the transcript swap; `restoreScrollState` queues the
   * target tab's saved position (or the bottom for a brand-new tab).
   */
  captureScrollState?: () => TabScrollState | null;
  restoreScrollState?: (state?: TabScrollState | null) => void;
  t: TFunction;
}

export function useSessionTabs({
  currentSessionId,
  currentSessionIdRef,
  setCurrentSessionId,
  sessionTitle,
  setCustomSessionTitle,
  updateHistoryTitle,
  messages,
  setMessages,
  loading,
  setLoading,
  streamingActive,
  setStreamingActive,
  isThinking,
  setIsThinking,
  status,
  setStatus,
  sessionLoading: _sessionLoading,
  setSessionLoading,
  revertBoundaryId,
  setRevertBoundaryId,
  isCompacting,
  setIsCompacting,
  compactingStartTime,
  setCompactingStartTime,
  taskEvents,
  setTaskEvents,
  sseTodos,
  setSseTodos,
  subagentHistories,
  setSubagentHistories,
  filteredFileChanges,
  setFilteredFileChanges,
  permissionMode,
  setPermissionMode,
  selectedModel,
  setSelectedModel,
  reasoningEffort,
  setReasoningEffort,
  contextInfo,
  setContextInfo,
  availableModels = [],
  currentProvider = 'opencode',
  usagePercentage = 0,
  setUsagePercentage,
  usageUsedTokens,
  setUsageUsedTokens,
  usageMaxTokens,
  setUsageMaxTokens,
  loadingStartTime,
  setLoadingStartTime,
  isShared,
  setCurrentView,
  chatInputRef,
  defaultAutoOpenFileEnabled,
  forceCreateNewSession: _forceCreateNewSession,
  loadHistorySession,
  streamingRuntimeRefs,
  captureScrollState,
  restoreScrollState,
  t,
}: UseSessionTabsOptions) {
  const [tabStore] = useState(() => new TabStore([
    {
      id: 'tab-1',
      sessionId: null,
      title: t('common.newSession', { defaultValue: '新会话' }),
      messages: [],
      isShared: false,
      autoOpenFileEnabled: defaultAutoOpenFileEnabled ?? false,
      permissionMode: permissionMode || 'default',
      selectedModel: selectedModel,
      reasoningEffort: reasoningEffort || 'default',
      filteredFileChanges: [],
      contextInfo: contextInfo ?? null,
      statusPanelExpanded: true,
      statusPanelTab: null,
      turnStartedAt: loadingStartTime ?? null,
      usagePercentage: usagePercentage ?? 0,
      usageUsedTokens: usageUsedTokens,
      usageMaxTokens: usageMaxTokens,
    },
  ], 'tab-1'));
  const tabs = useSyncExternalStore(tabStore.subscribe, tabStore.getTabs, tabStore.getTabs);
  const activeTabId = useSyncExternalStore(
    tabStore.subscribe,
    tabStore.getActiveTabId,
    tabStore.getActiveTabId,
  );
  const setTabs = tabStore.setTabs;
  const setActiveTabId = tabStore.setActiveTabId;
  const historyLoadingTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (historyLoadingTimeoutRef.current) {
        clearTimeout(historyLoadingTimeoutRef.current);
      }
    };
  }, []);

  // Keep ref to avoid stale closures in listeners
  const tabsRef = useRef(tabs);
  useEffect(() => {
    tabsRef.current = tabs;
  }, [tabs]);

  const activeTabIdRef = useRef(activeTabId);
  useEffect(() => {
    activeTabIdRef.current = activeTabId;
  }, [activeTabId]);

  /**
   * Streaming runtime per tab, keyed by tab id.
   *
   * Kept out of `SessionTabData` on purpose: every background delta clones the
   * tab object (`{...tab}`), so embedding a 10-field snapshot there would make
   * that hot path copy it on every delta.
   */
  const [runtimeStore] = useState(() => new TabRuntimeStore());
  const [draftStore] = useState(() => new TabDraftStore());

  // Real-time runtime state ref to completely eliminate stale closures when saving snapshots
  const runtimeStateRef = useRef({
    messages,
    currentSessionId,
    sessionTitle,
    loading,
    streamingActive,
    isThinking,
    status,
    revertBoundaryId,
    isCompacting,
    compactingStartTime,
    taskEvents,
    sseTodos,
    subagentHistories,
    filteredFileChanges,
    permissionMode,
    selectedModel,
    reasoningEffort,
    contextInfo,
    loadingStartTime,
    usagePercentage,
    usageUsedTokens,
    usageMaxTokens,
  });

  useEffect(() => {
    runtimeStateRef.current = {
      messages,
      currentSessionId,
      sessionTitle,
      loading,
      streamingActive,
      isThinking,
      status,
      revertBoundaryId,
      isCompacting,
      compactingStartTime,
      taskEvents,
      sseTodos,
      subagentHistories,
      filteredFileChanges,
      permissionMode,
      selectedModel,
      reasoningEffort,
      contextInfo,
      loadingStartTime,
      usagePercentage,
      usageUsedTokens,
      usageMaxTokens,
    };
  }, [
    messages,
    currentSessionId,
    sessionTitle,
    loading,
    streamingActive,
    isThinking,
    status,
    revertBoundaryId,
    isCompacting,
    compactingStartTime,
    taskEvents,
    sseTodos,
    subagentHistories,
    filteredFileChanges,
    permissionMode,
    selectedModel,
    reasoningEffort,
    contextInfo,
    loadingStartTime,
    usagePercentage,
    usageUsedTokens,
    usageMaxTokens,
  ]);

  // Sync active tab's metadata as conversation progresses
  useEffect(() => {
    setTabs((prev) =>
      prev.map((tab) => {
        if (tab.id === activeTabId) {
          return {
            ...tab,
            sessionId: currentSessionId ?? tab.sessionId,
            title: (!sessionTitle || sessionTitle === t('common.newSession')) ? tab.title : (sessionTitle || tab.title),
            isShared,
            messages,
            loading,
            streamingActive,
            isThinking,
            status,
            revertBoundaryId,
            isCompacting,
            compactingStartTime,
            taskEvents,
            sseTodos,
            subagentHistories,
            filteredFileChanges,
            permissionMode,
            selectedModel,
            reasoningEffort,
            contextInfo,
            turnStartedAt: loadingStartTime ?? tab.turnStartedAt,
            usagePercentage: usagePercentage ?? tab.usagePercentage,
            usageUsedTokens: usageUsedTokens !== undefined ? usageUsedTokens : tab.usageUsedTokens,
            usageMaxTokens: usageMaxTokens !== undefined ? usageMaxTokens : tab.usageMaxTokens,
            messageBaseIndex: typeof window.__messageBaseIndex === 'number' ? window.__messageBaseIndex : tab.messageBaseIndex,
            opencodeListStart: typeof window.__opencodeListStart === 'number' ? window.__opencodeListStart : tab.opencodeListStart,
            prependedHistoryMessageCount: typeof window.__prependedHistoryMessageCount === 'number' ? window.__prependedHistoryMessageCount : tab.prependedHistoryMessageCount,
          };
        }
        return tab;
      })
    );
  }, [
    activeTabId,
    currentSessionId,
    sessionTitle,
    isShared,
    messages,
    loading,
    streamingActive,
    isThinking,
    status,
    revertBoundaryId,
    isCompacting,
    compactingStartTime,
    taskEvents,
    sseTodos,
    subagentHistories,
    filteredFileChanges,
    permissionMode,
    selectedModel,
    reasoningEffort,
    contextInfo,
    loadingStartTime,
    usagePercentage,
    usageUsedTokens,
    usageMaxTokens,
  ]);

  const isActiveSession = useCallback((sessionId: string | null | undefined): boolean => {
    if (!sessionId) return false;
    const currentActive = tabsRef.current.find((t) => t.id === activeTabIdRef.current);

    // `currentSessionIdRef` is the authority on "what the active view shows".
    // `tab.sessionId` is only its projection and can lag: `loadHistorySession`
    // swaps the visible session without rebinding the tab. Trusting the stale
    // projection would keep treating the *outgoing* session as active and
    // interleave its deltas into the new session's transcript.
    const viewSessionId = currentSessionIdRef?.current;
    if (viewSessionId) {
      if (viewSessionId === sessionId) return true;
      // The active tab stays addressable by its own tab id regardless of which
      // session it currently displays.
      return !!currentActive && currentActive.id === sessionId;
    }

    if (!currentActive) return false;
    // A tab is addressable by its own id whether or not a session is bound yet.
    if (currentActive.id === sessionId) return true;
    return currentActive.sessionId === sessionId;
  }, [currentSessionIdRef]);

  /**
   * Apply `updater` to the (single) background tab matching `predicate`.
   *
   * @returns true when a tab matched. Callers MUST check this: a false return
   *     means the write had nowhere to go, and silently ignoring it is how
   *     prompts for a session whose tab did not exist yet were lost outright
   *     (the server later settled them as rejected — the "已取消" card that
   *     never showed a dialog).
   */
  const updateBackgroundTab = useCallback((
    predicate: (tab: SessionTabData) => boolean,
    updater: (tab: SessionTabData) => void
  ): boolean => {
    // Resolve the match against the current tabs synchronously: React may defer
    // (or double-invoke, under StrictMode) the updater, so a flag set inside it
    // would not be a reliable answer for the caller.
    const snapshot = tabsRef.current;
    let matchedId: string | null = null;
    for (let i = 0; i < snapshot.length; i++) {
      if (predicate(snapshot[i])) {
        matchedId = snapshot[i].id;
        break;
      }
    }
    if (matchedId === null) {
      // Nothing matched — return the previous array so an unknown session's
      // background write costs no re-render at all (it used to rebuild the whole
      // tab list on every delta of every session).
      return false;
    }
    const targetId = matchedId;
    setTabs((prev) => {
      const idx = prev.findIndex((t) => t.id === targetId);
      if (idx === -1) return prev;
      const next = prev.slice();
      const clone = { ...next[idx] };
      updater(clone);
      next[idx] = clone;
      return next;
    });
    return true;
  }, []);

  /**
   * Update one tab by its stable tab id. Events that already carry a tabId use
   * this directly instead of searching by sessionId: a tab may not be bound yet,
   * and a background event must never fall through to the active tab.
   */
  const updateTab = useCallback((tabId: string, updater: (tab: SessionTabData) => void): boolean => {
    if (!tabId) return false;
    const matched = tabsRef.current.some((tab) => tab.id === tabId);
    if (!matched) return false;
    setTabs((prev) => {
      const idx = prev.findIndex((tab) => tab.id === tabId);
      if (idx === -1) return prev;
      const next = prev.slice();
      const clone = { ...next[idx] };
      updater(clone);
      next[idx] = clone;
      return next;
    });
    return true;
  }, []);

  /** Apply a cleanup to every tab. Used by force-close signals that carry no tab id. */
  const updateAllTabs = useCallback((updater: (tab: SessionTabData) => void) => {
    setTabs((prev) => prev.map((tab) => {
      const clone = { ...tab };
      updater(clone);
      return clone;
    }));
  }, []);

  const getTabIdForSession = useCallback((sessionId: string | null | undefined): string | null => {
    if (!sessionId) return null;
    const match = tabsRef.current.find(
      (tab) => tab.sessionId === sessionId || tab.id === sessionId,
    );
    return match?.id ?? null;
  }, []);

  const isActiveTab = useCallback((tabId: string | null | undefined): boolean => {
    return !!tabId && tabId === activeTabIdRef.current;
  }, []);

  const updateTabSessionId = useCallback((tabId: string, sessionId: string) => {
    setTabs((prev) =>
      prev.map((tab) => {
        if (tab.id === tabId || tab.sessionId === tabId) {
          return { ...tab, sessionId };
        }
        return tab;
      })
    );
    if (activeTabIdRef.current === tabId) {
      setCurrentSessionId?.(sessionId);
    }
  }, [setCurrentSessionId]);

  const updateTabTitle = useCallback(
    (sessionId: string, newTitle: string) => {
      const trimmed = newTitle?.trim();
      if (!trimmed || !sessionId) return;
      // Strict match: a title belongs only to the tab bound to that session.
      // In particular, never route a background session's title to an unbound
      // draft tab — that made a newly created session inherit the previous
      // session's AI-generated title.
      const target = tabsRef.current.find((t) => t.sessionId === sessionId);
      if (!target) return;
      setTabs((prev) =>
        prev.map((tab) => (tab.sessionId === sessionId ? { ...tab, title: trimmed } : tab))
      );
      target.title = trimmed;
      if (target.id === activeTabIdRef.current) {
        setCustomSessionTitle(trimmed);
      }
    },
    [setCustomSessionTitle]
  );

  // Forward declaration ref for closeTab
  const closeTabRef = useRef<(tabId: string) => void>(() => {});

  const closeTabBySessionId = useCallback((sessionId: string) => {
    if (!sessionId) return;
    const target = tabsRef.current.find((t) => t.sessionId === sessionId);
    if (target) {
      closeTabRef.current(target.id);
    }
  }, []);

  useEffect(() => {
    window.__sessionTabsStore = {
      getActiveTabId: () => activeTabIdRef.current,
      getActiveSessionId: () => {
        // The view's authority is `currentSessionIdRef`; `tab.sessionId` can lag
        // behind it after `loadHistorySession` swaps the visible session.
        if (currentSessionIdRef?.current) return currentSessionIdRef.current;
        const cur = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
        return cur?.sessionId ?? null;
      },
      getActiveTabAutoOpenFileEnabled: () => {
        const cur = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
        return cur?.autoOpenFileEnabled ?? (defaultAutoOpenFileEnabled ?? false);
      },
      getTabs: () => tabsRef.current,
      getDrafts: () => draftStore.getAll(),
      isActiveSession,
      isActiveTab,
      getTabIdForSession,
      updateTab,
      updateAllTabs,
      updateBackgroundTab,
      updateTabSessionId,
      updateTabTitle,
      closeTabBySessionId,
      archiveActiveStreamingRuntime,
      clearStreamingRuntimeFor,
    };
    return () => {
      delete window.__sessionTabsStore;
    };
  }, [isActiveSession, isActiveTab, getTabIdForSession, updateTab, updateAllTabs, updateBackgroundTab, updateTabSessionId, updateTabTitle, closeTabBySessionId, draftStore]);

  // Helper: Save current active tab snapshot using real-time runtimeStateRef
  // The ref bag is passed in as a plain object, so pin it in a ref to keep the
  // capture/archive helpers dependency-free (stable identities for callbacks).
  const streamingRuntimeRefsRef = useRef(streamingRuntimeRefs);
  useEffect(() => {
    streamingRuntimeRefsRef.current = streamingRuntimeRefs;
  }, [streamingRuntimeRefs]);

  /** Archive the live streaming runtime under the tab that is leaving the view. */
  const archiveStreamingRuntime = useCallback((tabId: string) => {
    const refs = streamingRuntimeRefsRef.current;
    if (!refs) return;
    runtimeStore.set(tabId, captureStreamingRuntime(refs));
  }, [runtimeStore]);

  /** Load the tab's archived runtime, or the default if it never streamed. */
  const restoreStreamingRuntimeFor = useCallback((tabId: string, streamingHint?: boolean) => {
    const refs = streamingRuntimeRefsRef.current;
    if (!refs) return;
    const snapshot = runtimeStore.get(tabId);
    if (snapshot) {
      restoreStreamingRuntime(refs, snapshot);
      return;
    }
    restoreStreamingRuntime(refs, DEFAULT_STREAMING_RUNTIME);
    // A tab that started streaming while in the background has tab-level flags
    // but no runtime of its own — `onStreamStart` returns early for inactive
    // sessions. Adopt the hint so the first delta does not have to rebuild it.
    if (streamingHint) {
      refs.isStreamingRef.current = true;
    }
  }, [runtimeStore]);

  /**
   * Exposed so `beginSessionTransition` can archive before it wipes the refs.
   *
   * "Loading a different session into this tab" does not go through
   * `selectTab`, so without this hook the outgoing session's accumulator would
   * be destroyed by `__resetTransientUiState` while its turn is still running
   * in the background.
   */
  const archiveActiveStreamingRuntime = useCallback(() => {
    archiveStreamingRuntime(activeTabIdRef.current);
  }, [archiveStreamingRuntime]);

  /** Drop the archived runtime of whichever tab owns this session (stream ended). */
  const clearStreamingRuntimeFor = useCallback((sessionId: string) => {
    if (!sessionId) return;
    const owner = tabsRef.current.find((tab) => tab.sessionId === sessionId);
    if (owner) runtimeStore.delete(owner.id);
  }, [runtimeStore]);

  /**
   * Point the host at a tab's session. Must run BEFORE any per-session bridge
   * call that follows it.
   *
   * `set_mode` / `set_model` / `set_reasoning_effort` now carry a session id, but
   * the host's "window's current session" pointer still feeds legacy paths and
   * window-level notifications. Keeping it aligned avoids those observing the
   * outgoing tab. Tabs without a session are skipped: they have no host target.
   */
  const syncHostActiveTabTo = useCallback((tabId: string | null | undefined) => {
    if (!tabId) return;
    sendBridgeEvent('tab_activate', JSON.stringify({ tabId }));
  }, []);

  /**
   * Apply a build/plan mode to one specific session.
   *
   * The host accepts `{"sessionId","mode"}` and routes it to that session. A tab
   * without a bound session has no host target — its mode travels with
   * `send_message` — so there is deliberately no bare-string fallback here.
   */
  const syncPermissionModeTo = useCallback(
    (sessionId: string | null | undefined, mode: string) => {
      if (!sessionId) return;
      sendBridgeEvent('set_mode', JSON.stringify({ sessionId, mode }));
    },
    [],
  );

  /**
   * Rebuild the tab strip after a webview reload.
   *
   * The reload destroys the React context, so every tab except the active one
   * disappears while Java keeps hosting all of them. Java pushes the session ids
   * it still holds; we recreate them as shells. Transcripts load on demand —
   * either when the user sends a message (which re-opens the session) or when
   * they re-open it from the history list (a same-session soft reload).
   */
  useEffect(() => {
    const restore = (json: string) => {
      let payload: { sessions?: Array<{ sessionId?: string }> };
      try {
        payload = JSON.parse(json) as { sessions?: Array<{ sessionId?: string }> };
      } catch (error) {
        cardDebugLog('[SessionTabs] restoreSessionTabs: invalid payload', error);
        return;
      }
      const incoming = (payload.sessions ?? [])
        .map((entry) => entry?.sessionId)
        .filter((id): id is string => typeof id === 'string' && id.length > 0);
      if (incoming.length === 0) return;

      setTabs((prev) => {
        const known = new Set<string>();
        for (const tab of prev) {
          if (tab.sessionId) known.add(tab.sessionId);
        }
        const additions: SessionTabData[] = [];
        for (const sessionId of incoming) {
          if (known.has(sessionId)) continue;
          known.add(sessionId);
          additions.push({
            id: createTabId(),
            sessionId,
            // The real title arrives with the transcript; a short id prefix keeps
            // the tab identifiable in the meantime.
            title: sessionId.length > 8 ? sessionId.slice(0, 8) : sessionId,
            messages: [],
            isShared: false,
            loading: false,
            streamingActive: false,
            permissionMode: 'default',
            reasoningEffort: 'default',
            filteredFileChanges: [],
            contextInfo: null,
          });
        }
        return additions.length > 0 ? [...prev, ...additions] : prev;
      });
    };

    window.restoreSessionTabs = restore;
    return () => {
      delete window.restoreSessionTabs;
    };
  }, []);

  const saveCurrentTabSnapshot = useCallback(() => {
    const currentActive = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
    if (currentActive) {
      const snap = runtimeStateRef.current;
      currentActive.messages = snap.messages;
      currentActive.sessionId = snap.currentSessionId;
      const isDefaultSnapTitle = !snap.sessionTitle || snap.sessionTitle === t('common.newSession');
      currentActive.title = isDefaultSnapTitle ? currentActive.title : (snap.sessionTitle || currentActive.title);
      currentActive.loading = snap.loading;
      currentActive.streamingActive = snap.streamingActive;
      currentActive.isThinking = snap.isThinking;
      currentActive.status = snap.status;
      currentActive.revertBoundaryId = snap.revertBoundaryId;
      currentActive.isCompacting = snap.isCompacting;
      currentActive.compactingStartTime = snap.compactingStartTime;
      currentActive.taskEvents = snap.taskEvents;
      currentActive.sseTodos = snap.sseTodos;
      currentActive.subagentHistories = snap.subagentHistories;
      currentActive.filteredFileChanges = snap.filteredFileChanges;
      currentActive.permissionMode = snap.permissionMode;
      currentActive.selectedModel = snap.selectedModel;
      currentActive.reasoningEffort = snap.reasoningEffort;
      currentActive.contextInfo = snap.contextInfo;
      currentActive.turnStartedAt = snap.loadingStartTime ?? window.__turnStartedAt ?? null;
      currentActive.usagePercentage = snap.usagePercentage;
      currentActive.usageUsedTokens = snap.usageUsedTokens;
      currentActive.usageMaxTokens = snap.usageMaxTokens;
      currentActive.messageBaseIndex = typeof window.__messageBaseIndex === 'number' ? window.__messageBaseIndex : currentActive.messageBaseIndex;
      currentActive.opencodeListStart = typeof window.__opencodeListStart === 'number' ? window.__opencodeListStart : currentActive.opencodeListStart;
      currentActive.prependedHistoryMessageCount = typeof window.__prependedHistoryMessageCount === 'number' ? window.__prependedHistoryMessageCount : currentActive.prependedHistoryMessageCount;
      const capturedScroll = captureScrollState?.();
      if (capturedScroll) {
        currentActive.scrollState = capturedScroll;
      }

      const currentDraftText = chatInputRef?.current?.getValue?.() ?? '';
      draftStore.set(currentActive.id, {
        text: currentDraftText,
        // Attachments and file tags live inside the input box (uncontrolled
        // mode), so they must be pulled out explicitly — writing an empty array
        // here silently discarded every pending upload on every tab switch.
        attachments: chatInputRef?.current?.getAttachments?.() ?? [],
        fileTags: chatInputRef?.current?.getFileTags?.() ?? [],
        quotes: chatInputRef?.current?.getQuotes?.() ?? {},
      });
      if (window.__dialogStore) {
        const dialogSnap = window.__dialogStore.getSnapshot();
        currentActive.pendingPermissions = dialogSnap.permissions;
        currentActive.pendingQuestions = dialogSnap.questions;
      }

      // The tab is leaving the viewport: freeze its streaming runtime so a
      // background turn keeps accumulating into this tab, not into the next one.
      archiveStreamingRuntime(currentActive.id);
    }
  }, [chatInputRef, archiveStreamingRuntime, captureScrollState, draftStore]);

  // Select tab (pure view switch: preserves background generation, never interrupts)
  const selectTab = useCallback(
    (targetTabId: string) => {
      if (targetTabId === activeTabIdRef.current) return;

      saveCurrentTabSnapshot();

      const targetTab = tabsRef.current.find((t) => t.id === targetTabId);
      if (!targetTab) return;

      // Whether this switch actually replaces the rendered transcript. A no-op
      // switch must not spend a full OSR publication pulse.
      const contentChanged = targetTab.messages !== runtimeStateRef.current.messages;

      activeTabIdRef.current = targetTabId;
      setActiveTabId(targetTabId);

      // A transition toward *another* session may still be in flight (its
      // history is loading). The guard is global, and the streaming paths bail
      // out on `__sessionTransitioning` unconditionally — so leaving it set
      // while the user looks at a different tab would silently drop every delta
      // that arrives for the tab now on screen. Abandon the transition instead.
      const pendingTransitionTarget = window.__sessionTransitionTargetSessionId ?? null;
      if (
        window.__sessionTransitioning
        && pendingTransitionTarget
        && pendingTransitionTarget !== targetTab.sessionId
      ) {
        releaseSessionTransition();
      }

      // Activate the target tab first so window-level host notifications see
      // the tab the user is switching to.
      syncHostActiveTabTo(targetTab.id);

      // Restore target tab's snapshot into active view
      if (currentSessionIdRef) {
        currentSessionIdRef.current = targetTab.sessionId;
      }
      setCurrentSessionId?.(targetTab.sessionId);
      setCustomSessionTitle(targetTab.title || null);
      // 同步 Tab 标题到 IDE ToolWindow，切换 Tab 时跟随标签页标题
      sendBridgeEvent('update_tab_title', targetTab.title || '');
      setMessages(targetTab.messages || []);
      // Queue the target tab's own reader position. The layout effect in
      // useScrollBehavior applies it after this commit and skips auto-follow,
      // so the outgoing tab's "at bottom" state cannot drag this reader down.
      restoreScrollState?.(targetTab.scrollState ?? null);
      // Exchange the streaming runtime immediately after the transcript swap:
      // the next delta must see this tab's accumulator, and the message index is
      // intentionally reset so the live assistant is re-located in the new array.
      restoreStreamingRuntimeFor(targetTab.id, targetTab.streamingActive);
      setLoading?.(targetTab.loading || false);
      setStreamingActive?.(targetTab.streamingActive || false);
      setIsThinking?.(targetTab.isThinking || false);
      setStatus?.(targetTab.status || '');
      setRevertBoundaryId?.(targetTab.revertBoundaryId ?? null);
      setIsCompacting?.(targetTab.isCompacting || false);
      setCompactingStartTime?.(targetTab.compactingStartTime ?? null);
      setTaskEvents?.(targetTab.taskEvents || {});
      setSseTodos?.(targetTab.sseTodos || null);
      setSubagentHistories?.(targetTab.subagentHistories || {});
      setFilteredFileChanges?.(targetTab.filteredFileChanges || []);

      // Restore per-tab sliding window cursors into window
      window.__messageBaseIndex = targetTab.messageBaseIndex ?? 0;
      window.__opencodeListStart = targetTab.opencodeListStart ?? 0;
      window.__prependedHistoryMessageCount = targetTab.prependedHistoryMessageCount ?? 0;

      // Restore session model & mode & reasoning effort.
      // `setSelectedModel` / `setReasoningEffort` are the App-level handlers and
      // already push their value to the host — do not send a second time here.
      const targetMode = targetTab.permissionMode || 'default';
      setPermissionMode?.(targetMode);
      syncPermissionModeTo(targetTab.sessionId, targetMode);

      const targetEffort = targetTab.reasoningEffort || 'default';
      setReasoningEffort?.(targetEffort);

      const resolvedModel = resolveSessionModel(
        targetTab.selectedModel,
        currentProvider || 'opencode',
        availableModels || [],
      );
      if (resolvedModel) {
        setSelectedModel?.(resolvedModel, { silent: true });
      }

      // Restore contextInfo if target tab has a specific snapshot
      const isAutoOpen = targetTab.autoOpenFileEnabled ?? (defaultAutoOpenFileEnabled ?? false);
      sendBridgeEvent('refresh_editor_context', JSON.stringify({ tabId: targetTab.id, enabled: isAutoOpen }));
      if (targetTab.contextInfo) {
        setContextInfo?.(targetTab.contextInfo);
      }

      // Restore turnStartedAt & usage tracking to prevent cross-session pollution
      const targetTurnStartedAt = targetTab.turnStartedAt ?? null;
      setLoadingStartTime?.(targetTurnStartedAt);
      window.__turnStartedAt = targetTurnStartedAt ?? undefined;

      setUsagePercentage?.(targetTab.usagePercentage ?? 0);
      setUsageUsedTokens?.(targetTab.usageUsedTokens);
      setUsageMaxTokens?.(targetTab.usageMaxTokens);

      // Restore input box draft. Order matters: text first, then attachments,
      // then file tags — `restoreFileTags` re-renders chips out of the restored
      // text, so it has to run last.
      if (chatInputRef?.current?.setValue) {
        const draft = draftStore.get(targetTab.id);
        chatInputRef.current.setValue(draft?.text ?? '');
        chatInputRef.current.setAttachments?.(draft?.attachments ?? []);
        chatInputRef.current.restoreFileTags?.(draft?.fileTags ?? []);
        chatInputRef.current.restoreQuoteTags?.(draft?.quotes ?? {});
      }

      // Switching tabs must NEVER show "loading history session" in status bar
      setSessionLoading?.(false);

      // Restore target tab's dialog state
      if (window.__dialogStore) {
        window.__dialogStore.restore(
          targetTab.pendingPermissions || [],
          targetTab.pendingQuestions || []
        );
      }

      // Publish the swapped transcript once. On OSR the surface still holds the
      // outgoing tab's backing image, and the next natural paint may be a long
      // way off (e.g. an idle session); the fence forces a clean full frame.
      if (contentChanged) {
        window.__requestSurfaceRepublish?.();
      }
    },
    [
      saveCurrentTabSnapshot,
      restoreStreamingRuntimeFor,
      restoreScrollState,
      syncHostActiveTabTo,
      syncPermissionModeTo,
      setCurrentSessionId,
      setCustomSessionTitle,
      setMessages,
      setLoading,
      setStreamingActive,
      setIsThinking,
      setStatus,
      setRevertBoundaryId,
      setIsCompacting,
      setCompactingStartTime,
      setTaskEvents,
      setSseTodos,
      setSubagentHistories,
      setFilteredFileChanges,
      setPermissionMode,
      setReasoningEffort,
      setSelectedModel,
      setContextInfo,
      setLoadingStartTime,
      setUsagePercentage,
      setUsageUsedTokens,
      setUsageMaxTokens,
      currentProvider,
      availableModels,
      setSessionLoading,
      chatInputRef,
      draftStore,
    ]
  );

  // Open session in tab (from history: jump if already open, create new tab if not)
  const openSessionInTab = useCallback(
    (
      sessionId: string,
      title?: string,
      historyModel?: string,
      historyMode?: string,
      historyEffort?: string
    ) => {
      // 1. If this session is already open in a tab, jump to it!
      const existingTab = tabsRef.current.find((t) => t.sessionId === sessionId);
      if (existingTab) {
        selectTab(existingTab.id);
        setCurrentView?.('chat');
        setSessionLoading?.(false);
        return;
      }

      const resolvedModel = resolveSessionModel(
        historyModel,
        currentProvider || 'opencode',
        availableModels || [],
      );
      const resolvedMode = (historyMode as PermissionMode) || 'default';
      const resolvedEffort = (historyEffort as ReasoningEffort) || 'default';

      // 2. Not open yet: check if current tab is a blank draft tab (no messages, no sessionId)
      const currentActive = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
      const isCurrentEmptyDraft =
        currentActive &&
        currentActive.sessionId === null &&
        (!messages || messages.length === 0) &&
        (!currentActive.messages || currentActive.messages.length === 0);

      const resolvedTitle = title || t('common.historySession', { defaultValue: '历史会话' });

      if (isCurrentEmptyDraft && currentActive) {
        // Reuse current blank draft tab
        const updatedTabs = tabsRef.current.map((t) =>
          t.id === currentActive.id
            ? {
                ...t,
                sessionId,
                title: resolvedTitle,
                pendingPermissions: [],
                pendingQuestions: [],
                selectedModel: resolvedModel,
                permissionMode: resolvedMode,
                reasoningEffort: resolvedEffort,
                filteredFileChanges: [],
                contextInfo: null,
                autoOpenFileEnabled: defaultAutoOpenFileEnabled ?? false,
                scrollState: undefined,
              }
            : t
        );
        tabsRef.current = updatedTabs;
        setTabs(updatedTabs);
        if (currentSessionIdRef) {
          currentSessionIdRef.current = sessionId;
        }
        setCurrentSessionId?.(sessionId);
        setCustomSessionTitle(resolvedTitle);
      } else {
        // Create new tab for this history session
        saveCurrentTabSnapshot();

        const newId = createTabId();
        const newTab: SessionTabData = {
          id: newId,
          sessionId,
          title: resolvedTitle,
          messages: [],
          isShared: false,
          loading: false,
          streamingActive: false,
          pendingPermissions: [],
          pendingQuestions: [],
          autoOpenFileEnabled: defaultAutoOpenFileEnabled ?? false,
          selectedModel: resolvedModel,
          permissionMode: resolvedMode,
          reasoningEffort: resolvedEffort,
          filteredFileChanges: [],
          contextInfo: null,
        };

        const updatedTabs = [...tabsRef.current, newTab];
        tabsRef.current = updatedTabs;
        activeTabIdRef.current = newId;
        setTabs(updatedTabs);
        setActiveTabId(newId);
        if (currentSessionIdRef) {
          currentSessionIdRef.current = sessionId;
        }
        setCurrentSessionId?.(sessionId);
        setCustomSessionTitle(resolvedTitle);
      }

      // Point the host at this session before the mode/model calls below.
      syncHostActiveTabTo(activeTabIdRef.current);

      // 同步 Tab 标题到 IDE ToolWindow，切换历史会话 Tab 时跟随标签页标题
      sendBridgeEvent('update_tab_title', resolvedTitle);

      setMessages([]);
      // A freshly opened tab starts at the newest message and must not inherit
      // the outgoing tab's paused/at-bottom flags.
      restoreScrollState?.(null);
      setLoading?.(false);
      setStreamingActive?.(false);
      setIsThinking?.(false);
      setStatus?.('');
      setRevertBoundaryId?.(null);
      setIsCompacting?.(false);
      setCompactingStartTime?.(null);
      setTaskEvents?.({});
      setSseTodos?.(null);
      setSubagentHistories?.({});
      setFilteredFileChanges?.([]);

      // Restore session model & mode & reasoning effort.
      // The App-level setters already push their value to the host, so the only
      // explicit call is the session-scoped mode.
      if (resolvedModel) {
        setSelectedModel?.(resolvedModel, { silent: true });
      }
      setPermissionMode?.(resolvedMode);
      syncPermissionModeTo(sessionId, resolvedMode);
      setReasoningEffort?.(resolvedEffort);

      // Reset timer & usage for newly opened history session
      setLoadingStartTime?.(null);
      window.__turnStartedAt = undefined;
      setUsagePercentage?.(0);
      setUsageUsedTokens?.(undefined);
      setUsageMaxTokens?.(undefined);

      chatInputRef?.current?.setValue?.('');
      setCurrentView?.('chat');

      // Only opening explicitly from history drawer triggers the loading bar
      setSessionLoading?.(true);

      // Safety guard: cancel any existing loading timeout and ensure automatic release after 8s
      if (historyLoadingTimeoutRef.current) {
        clearTimeout(historyLoadingTimeoutRef.current);
      }
      historyLoadingTimeoutRef.current = setTimeout(() => {
        setSessionLoading?.(false);
      }, 8000);

      // Loading fresh session resets active dialog state
      window.__dialogStore?.clearActive?.();

      // Load session via unified session management state machine
      if (loadHistorySession) {
        loadHistorySession(sessionId);
      } else {
        sendBridgeEvent('load_session', JSON.stringify({ sessionId, tabId: activeTabIdRef.current }));
      }
    },
    [
      selectTab,
      saveCurrentTabSnapshot,
      restoreScrollState,
      messages,
      defaultAutoOpenFileEnabled,
      loadHistorySession,
      syncHostActiveTabTo,
      t,
      currentProvider,
      availableModels,
      setCurrentSessionId,
      setCustomSessionTitle,
      setMessages,
      setLoading,
      setStreamingActive,
      setIsThinking,
      setStatus,
      setRevertBoundaryId,
      setIsCompacting,
      setCompactingStartTime,
      setTaskEvents,
      setSseTodos,
      setSubagentHistories,
      setFilteredFileChanges,
      setSelectedModel,
      setPermissionMode,
      setReasoningEffort,
      setContextInfo,
      setLoadingStartTime,
      setUsagePercentage,
      setUsageUsedTokens,
      setUsageMaxTokens,
      setCurrentView,
      setSessionLoading,
      chatInputRef,
    ]
  );

  // Create new session tab (independent tab, does NOT interrupt background tabs)
  const createTab = useCallback(() => {
    saveCurrentTabSnapshot();

    // Ensure at most ONE unbound new session tab exists across all tabs
    const existingUnbound = tabsRef.current.find((t) => !t.sessionId);
    if (existingUnbound) {
      selectTab(existingUnbound.id);
      return;
    }

    const defaultModel = getFirstPreferredModelId(
      currentProvider || 'opencode',
      availableModels || [],
    ) || '';

    const newId = createTabId();
    const newTab: SessionTabData = {
      id: newId,
      sessionId: null,
      title: t('common.newSession', { defaultValue: '新会话' }),
      messages: [],
      messageBaseIndex: 0,
      opencodeListStart: 0,
      prependedHistoryMessageCount: 0,
      isShared: false,
      loading: false,
      streamingActive: false,
      pendingPermissions: [],
      pendingQuestions: [],
      autoOpenFileEnabled: defaultAutoOpenFileEnabled ?? false,
      selectedModel: defaultModel,
      permissionMode: 'default',
      reasoningEffort: 'default',
      filteredFileChanges: [],
      contextInfo: null,
    };

    const updatedTabs = [...tabsRef.current, newTab];
    tabsRef.current = updatedTabs;
    activeTabIdRef.current = newId;
    setTabs(updatedTabs);
    setActiveTabId(newId);
    if (currentSessionIdRef) {
      currentSessionIdRef.current = null;
    }
    setCurrentSessionId?.(null);
    setCustomSessionTitle(null);
    setMessages([]);
    restoreScrollState?.(null);
    window.__messageBaseIndex = 0;
    window.__opencodeListStart = 0;
    window.__prependedHistoryMessageCount = 0;
    setLoading?.(false);
    setStreamingActive?.(false);
    setIsThinking?.(false);
    setStatus?.('');
    setRevertBoundaryId?.(null);
    setIsCompacting?.(false);
    setCompactingStartTime?.(null);
    setTaskEvents?.({});
    setSseTodos?.(null);
    setSubagentHistories?.({});
    setFilteredFileChanges?.([]);

    // A brand-new tab has no host session yet, so model/mode/effort are local
    // UI state only. Sending set_model / set_mode / set_reasoning_effort here
    // would land on "the window's current session" — the previous tab — and the
    // first send_message carries the same values to the new session.
    if (defaultModel) {
      setSelectedModel?.(defaultModel);
    }
    setPermissionMode?.('default');
    setReasoningEffort?.('default');

    // Reset turn timer & usage metrics for new tab
    setLoadingStartTime?.(null);
    window.__turnStartedAt = undefined;
    setUsagePercentage?.(0);
    setUsageUsedTokens?.(undefined);
    setUsageMaxTokens?.(undefined);

    chatInputRef?.current?.setValue?.('');
    setSessionLoading?.(false);
    setCurrentView?.('chat');

    // Brand new tab has no pending dialogs
    window.__dialogStore?.clearActive?.();

    // 同步新 Tab 标题到 IDE ToolWindow
    sendBridgeEvent('update_tab_title', newTab.title);
    syncHostActiveTabTo(newId);
  }, [
    saveCurrentTabSnapshot,
    restoreScrollState,
    t,
    defaultAutoOpenFileEnabled,
    currentProvider,
    availableModels,
    setCurrentSessionId,
    setCustomSessionTitle,
    setMessages,
    setLoading,
    setStreamingActive,
    setIsThinking,
    setStatus,
    setRevertBoundaryId,
    setIsCompacting,
    setCompactingStartTime,
    setTaskEvents,
    setSseTodos,
    setSubagentHistories,
    setFilteredFileChanges,
    setSelectedModel,
    setPermissionMode,
    setReasoningEffort,
    setContextInfo,
    setLoadingStartTime,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setSessionLoading,
    setCurrentView,
    chatInputRef,
    syncHostActiveTabTo,
  ]);

  // Expose the local tab creator to session commands (/new, /clear, header
  // button) without making them depend on the hook instance. Creating a tab is
  // pure UI: no host session is created until the first send.
  useEffect(() => {
    window.__openNewSessionTab = createTab;
    return () => {
      if (window.__openNewSessionTab === createTab) {
        delete window.__openNewSessionTab;
      }
    };
  }, [createTab]);

  // Close tab
  const closeTab = useCallback(
    (tabId: string) => {
      const closingTab = tabsRef.current.find((t) => t.id === tabId);
      if (closingTab?.loading && closingTab.sessionId) {
        // Only interrupt this specific session if it was actively generating
        sendBridgeEvent('interrupt_session', JSON.stringify({ sessionId: closingTab.sessionId }));
      }
      if (closingTab?.sessionId) {
        sendBridgeEvent('close_session', JSON.stringify({ sessionId: closingTab.sessionId }));
        clearSessionSequenceBarrier(closingTab.sessionId);
      }
      // Drop the archived runtime with the tab; a recycled tab id must never
      // inherit a dead session's accumulator.
      runtimeStore.delete(tabId);
      draftStore.delete(tabId);

      setTabs((prev) => {
        if (prev.length <= 1) {
          const defaultModel = getFirstPreferredModelId(
            currentProvider || 'opencode',
            availableModels || [],
          ) || '';
          const freshTab: SessionTabData = {
            id: createTabId(),
            sessionId: null,
            title: t('common.newSession', { defaultValue: '新会话' }),
            messages: [],
            isShared: false,
            autoOpenFileEnabled: defaultAutoOpenFileEnabled ?? false,
            selectedModel: defaultModel,
            permissionMode: 'default',
            reasoningEffort: 'default',
            filteredFileChanges: [],
            contextInfo: null,
          };
          setActiveTabId(freshTab.id);
          setCurrentSessionId?.(null);
          setCustomSessionTitle(null);
          setMessages([]);
          restoreScrollState?.(null);
          setLoading?.(false);
          setStreamingActive?.(false);
          setIsThinking?.(false);
          setStatus?.('');
          setRevertBoundaryId?.(null);
          setIsCompacting?.(false);
          setCompactingStartTime?.(null);
          setTaskEvents?.({});
          setSseTodos?.(null);
          setSubagentHistories?.({});
          setFilteredFileChanges?.([]);
          // Fresh unbound tab: keep model/mode/effort local; the first send
          // carries them to the new session. Never write the outgoing session.
          if (defaultModel) {
            setSelectedModel?.(defaultModel);
          }
          setPermissionMode?.('default');
          setReasoningEffort?.('default');
          setLoadingStartTime?.(null);
          window.__turnStartedAt = undefined;
          setUsagePercentage?.(0);
          setUsageUsedTokens?.(undefined);
          setUsageMaxTokens?.(undefined);
          setSessionLoading?.(false);
          // Last tab closed: the fresh tab must not inherit the dead session's
          // accumulator / turnId / message index.
          if (streamingRuntimeRefsRef.current) {
            clearStreamingRuntime(streamingRuntimeRefsRef.current);
          }
          return [freshTab];
        }

        const nextTabs = prev.filter((t) => t.id !== tabId);
        if (tabId === activeTabIdRef.current) {
          const closedIndex = prev.findIndex((t) => t.id === tabId);
          const nextActive = nextTabs[Math.max(0, closedIndex - 1)];
          if (nextActive) {
            activeTabIdRef.current = nextActive.id;
            setActiveTabId(nextActive.id);
            // Closing the active tab moves the view to a sibling: the host must
            // follow it, or the next turn is sent to the session we just closed.
            syncHostActiveTabTo(nextActive.id);
            if (currentSessionIdRef) {
              currentSessionIdRef.current = nextActive.sessionId;
            }
            setCurrentSessionId?.(nextActive.sessionId);
            setCustomSessionTitle(nextActive.title || null);
            setMessages(nextActive.messages || []);
            restoreScrollState?.(nextActive.scrollState ?? null);
            restoreStreamingRuntimeFor(nextActive.id, nextActive.streamingActive);
            setLoading?.(nextActive.loading || false);
            setStreamingActive?.(nextActive.streamingActive || false);
            setIsThinking?.(nextActive.isThinking || false);
            setStatus?.(nextActive.status || '');
            setRevertBoundaryId?.(nextActive.revertBoundaryId ?? null);
            setIsCompacting?.(nextActive.isCompacting || false);
            setCompactingStartTime?.(nextActive.compactingStartTime ?? null);
            setTaskEvents?.(nextActive.taskEvents || {});
            setSseTodos?.(nextActive.sseTodos || null);
            setSubagentHistories?.(nextActive.subagentHistories || {});
            setFilteredFileChanges?.(nextActive.filteredFileChanges || []);

            const nextMode = nextActive.permissionMode || 'default';
            setPermissionMode?.(nextMode);
            syncPermissionModeTo(nextActive.sessionId, nextMode);

            const nextEffort = nextActive.reasoningEffort || 'default';
            setReasoningEffort?.(nextEffort);

            const resolvedModel = resolveSessionModel(
              nextActive.selectedModel,
              currentProvider || 'opencode',
              availableModels || [],
            );
            if (resolvedModel) {
              setSelectedModel?.(resolvedModel, { silent: true });
            }

            if (nextActive.contextInfo) {
              setContextInfo?.(nextActive.contextInfo);
            }

            // Restore turnStartedAt & usage tracking for the switched active tab
            const nextTurnStartedAt = nextActive.turnStartedAt ?? null;
            setLoadingStartTime?.(nextTurnStartedAt);
            window.__turnStartedAt = nextTurnStartedAt ?? undefined;

            setUsagePercentage?.(nextActive.usagePercentage ?? 0);
            setUsageUsedTokens?.(nextActive.usageUsedTokens);
            setUsageMaxTokens?.(nextActive.usageMaxTokens);

            setSessionLoading?.(false);

            if (window.__dialogStore) {
              window.__dialogStore.restore(
                nextActive.pendingPermissions || [],
                nextActive.pendingQuestions || []
              );
            }
          }
        }
        return nextTabs;
      });
    },
    [
      t,
      defaultAutoOpenFileEnabled,
      currentProvider,
      availableModels,
      restoreScrollState,
      setCurrentSessionId,
      setCustomSessionTitle,
      setMessages,
      setLoading,
      setStreamingActive,
      setIsThinking,
      setStatus,
      setRevertBoundaryId,
      setIsCompacting,
      setCompactingStartTime,
      setTaskEvents,
      setSseTodos,
      setSubagentHistories,
      setFilteredFileChanges,
      setPermissionMode,
      setReasoningEffort,
      setSelectedModel,
      setContextInfo,
      setLoadingStartTime,
      setUsagePercentage,
      setUsageUsedTokens,
      setUsageMaxTokens,
      setSessionLoading,
      runtimeStore,
      restoreStreamingRuntimeFor,
    ]
  );

  // Keep closeTabRef updated
  useEffect(() => {
    closeTabRef.current = closeTab;
  }, [closeTab]);

  // Rename tab
  const renameTab = useCallback(
    (tabId: string, newTitle: string) => {
      setTabs((prev) =>
        prev.map((t) => (t.id === tabId ? { ...t, title: newTitle, isCustomTitle: true } : t))
      );
      if (tabId === activeTabIdRef.current) {
        setCustomSessionTitle(newTitle);
        if (currentSessionId) {
          updateHistoryTitle(currentSessionId, newTitle);
        }
      } else {
        const target = tabsRef.current.find((t) => t.id === tabId);
        if (target?.sessionId) {
          updateHistoryTitle(target.sessionId, newTitle);
        }
      }
    },
    [currentSessionId, setCustomSessionTitle, updateHistoryTitle]
  );

  // Fork tab
  const forkTab = useCallback(
    (tab: SessionTabData) => {
      if (tab.sessionId) {
        sendBridgeEvent('fork_session', JSON.stringify({ sessionId: tab.sessionId, type: 'full' }));
      }
    },
    []
  );

  // Expose window.createSessionTab for IDEA title bar action (+)
  useEffect(() => {
    (window as any).createSessionTab = createTab;
    return () => {
      delete (window as any).createSessionTab;
    };
  }, [createTab]);

  const activeTab = tabs.find((t) => t.id === activeTabId);
  const activeTabAutoOpenFileEnabled = activeTab?.autoOpenFileEnabled ?? (defaultAutoOpenFileEnabled ?? false);
  const statusPanelExpanded = activeTab?.statusPanelExpanded ?? true;
  const statusPanelTab = activeTab?.statusPanelTab ?? null;

  const setStatusPanelTab = useCallback((tab: TabType | null) => {
    setTabs((prev) => prev.map((item) => (
      item.id === activeTabIdRef.current
        ? { ...item, statusPanelTab: tab }
        : item
    )));
  }, []);

  const toggleStatusPanel = useCallback(() => {
    setTabs((prev) => prev.map((item) => (
      item.id === activeTabIdRef.current
        ? { ...item, statusPanelExpanded: !(item.statusPanelExpanded ?? true) }
        : item
    )));
  }, []);

  const setActiveTabAutoOpenFileEnabled = useCallback((enabled: boolean) => {
    setTabs((prev) =>
      prev.map((tab) => {
        if (tab.id === activeTabIdRef.current) {
          return { ...tab, autoOpenFileEnabled: enabled };
        }
        return tab;
      })
    );
    sendBridgeEvent('refresh_editor_context', JSON.stringify({ tabId: activeTabIdRef.current, enabled }));
  }, []);

  // Reorder tabs (supports long-press drag and drop reordering)
  const reorderTabs = useCallback((sourceIndex: number, targetIndex: number) => {
    if (sourceIndex === targetIndex) return;
    setTabs((prev) => {
      if (sourceIndex < 0 || sourceIndex >= prev.length || targetIndex < 0 || targetIndex >= prev.length) {
        return prev;
      }
      const next = [...prev];
      const [moved] = next.splice(sourceIndex, 1);
      next.splice(targetIndex, 0, moved);
      return next;
    });
  }, []);

  return {
    tabs,
    activeTabId,
    selectTab,
    createTab,
    closeTab,
    renameTab,
    forkTab,
    reorderTabs,
    openSessionInTab,
    activeTabAutoOpenFileEnabled,
    setActiveTabAutoOpenFileEnabled,
    statusPanelExpanded,
    statusPanelTab,
    setStatusPanelTab,
    toggleStatusPanel,
  };
}
