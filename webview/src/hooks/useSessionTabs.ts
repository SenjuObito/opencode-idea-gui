import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage } from '../types';
import type { ContextInfo } from './useWindowCallbacks';
import type { TabScrollState } from './useScrollBehavior';
import type { SessionTabData } from '../components/ChatHeader/types';
import type { TabType } from '../components/StatusPanel/types';
import { TabStore, createTabId } from '../store/tabStore';
import { TabDraftStore } from '../store/tabDraftStore';
import { TabStatusStore, statusSnapshotOf } from '../store/tabStatusStore';
import { TabPromptStore } from '../store/tabPromptStore';
import { TabMessageStore } from '../store/tabMessageStore';
import type { ModelInfo, PermissionMode, ReasoningEffort } from '../components/ChatInputBox/types';
import { getFirstPreferredModelId, resolveSessionModel } from '../components/ChatInputBox/modelSelectUtils';
import { sendBridgeEvent, sendInterrupt, sendTabCommand, cardDebugLog } from '../utils/bridge';
import { observeHostEpoch, resetHostDispatcherEpochs } from '../utils/hostDispatcher';
import { clearSessionSequenceBarrier } from './windowCallbacks/sequenceBarrier';
import { clearTransition, endTransition, transitionTargetFor } from './windowCallbacks/tabTransition';

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
   * Per-tab runtime ownership (see `useStreamingMessages`). The tab hook only
   * needs to tell the owner when a tab disappears or when a background turn
   * starts; it never touches the runtime of another tab.
   */
  deleteTabRuntime?: (tabId: string) => void;
  setTabRuntimeStreaming?: (tabId: string, streaming: boolean) => void;
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
  deleteTabRuntime,
  setTabRuntimeStreaming,
  captureScrollState,
  restoreScrollState,
  t,
}: UseSessionTabsOptions) {
  const [tabStore] = useState(() => new TabStore([
    {
      id: 'tab-1',
      sessionId: null,
      messageStore: new TabMessageStore(),
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
  const [draftStore] = useState(() => new TabDraftStore());
  const [statusStore] = useState(() => new TabStatusStore());
  const [promptStore] = useState(() => new TabPromptStore());

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
    (sessionId: string, newTitle: string, tabId?: string | null) => {
      const trimmed = newTitle?.trim();
      if (!trimmed || !sessionId) return;
      // The host names the owning tab: routing by tab id is exact. The session
      // lookup stays as the fallback for hosts that do not stamp it.
      //
      // Strict match otherwise: a title belongs only to the tab bound to that
      // session. In particular, never route a background session's title to an
      // unbound draft tab — that made a newly created session inherit the
      // previous session's AI-generated title.
      const target = tabId
        ? tabsRef.current.find((t) => t.id === tabId)
        : tabsRef.current.find((t) => t.sessionId === sessionId);
      if (!target) return;
      setTabs((prev) =>
        prev.map((tab) => (tab.id === target.id ? { ...tab, title: trimmed } : tab))
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
      clearStreamingRuntimeFor,
    };
    return () => {
      delete window.__sessionTabsStore;
    };
  }, [isActiveSession, isActiveTab, getTabIdForSession, updateTab, updateAllTabs, updateBackgroundTab, updateTabSessionId, updateTabTitle, closeTabBySessionId, draftStore]);

  // Helper: Save current active tab snapshot using real-time runtimeStateRef
  // The ref bag is passed in as a plain object, so pin it in a ref to keep the
  // capture/archive helpers dependency-free (stable identities for callbacks).
  /**
   * Drop the runtime of whichever tab owns this session (the turn ended).
   *
   * <p>The runtime map is the owner, so ending a turn means the tab's runtime
   * goes away; a later turn in the same tab starts from a clean accumulator.</p>
   */
  const clearStreamingRuntimeFor = useCallback((sessionId: string) => {
    const owner = getTabIdForSession(sessionId);
    if (owner) deleteTabRuntime?.(owner);
  }, [getTabIdForSession, deleteTabRuntime]);

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
    sendTabCommand(tabId, 'tab.activate');
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
   * The reload destroys the React context, so every tab except the one React
   * just created disappears while Java keeps hosting all of them. Java pushes
   * the stable tab ids it still holds, in order, plus the active tab. We rebuild
   * by tab id (never by inventing ids from session ids) and adopt the active
   * tab so a reload cannot silently switch the user to another session.
   *
   * Transcripts load on demand — either when the user sends a message (which
   * re-opens the session) or from the history list (a same-session soft reload).
   */
  useEffect(() => {
    const resync = (json: string) => {
      let payload: {
        type?: string;
        activeTabId?: string;
        tabs?: Array<{
          tabId?: string;
          sessionId?: string | null;
          title?: string;
          streaming?: boolean;
          loading?: boolean;
          epoch?: number;
        }>;
      };
      try {
        payload = JSON.parse(json) as typeof payload;
      } catch (error) {
        cardDebugLog('[SessionTabs] onTabResync: invalid payload', error);
        return;
      }
      const incoming = (payload?.tabs ?? []).filter(
        (entry): entry is NonNullable<typeof entry> =>
          !!entry && typeof entry.tabId === 'string' && entry.tabId.length > 0,
      );
      if (incoming.length === 0) return;

      // The host is authoritative about which binding is current. Seed the
      // dispatcher so an event produced before this reload is not replayed
      // over the resynced tab.
      resetHostDispatcherEpochs();
      for (const entry of incoming) {
        if (typeof entry.epoch === 'number') {
          observeHostEpoch(entry.tabId!, entry.epoch);
        }
      }

      const previousById = new Map(tabsRef.current.map((tab) => [tab.id, tab]));
      const nextTabs: SessionTabData[] = incoming.map((entry) => {
        const previous = previousById.get(entry.tabId!);
        const sessionId = typeof entry.sessionId === 'string' && entry.sessionId
          ? entry.sessionId
          : (previous?.sessionId ?? null);
        const title = entry.title || previous?.title
          || t('common.newSession', { defaultValue: '新会话' });
        const streamingActive = typeof entry.streaming === 'boolean'
          ? entry.streaming
          : (previous?.streamingActive ?? false);
        const loading = typeof entry.loading === 'boolean'
          ? entry.loading
          : (previous?.loading ?? false);
        if (previous) {
          return { ...previous, sessionId, title, streamingActive, loading };
        }
        return {
          id: entry.tabId!,
          sessionId,
          title,
          messageStore: new TabMessageStore(),
          messages: [],
          isShared: false,
          loading,
          streamingActive,
          permissionMode: 'default',
          reasoningEffort: 'default',
          filteredFileChanges: [],
          contextInfo: null,
          pendingPermissions: [],
          pendingQuestions: [],
        };
      });

      tabsRef.current = nextTabs;
      setTabs(nextTabs);

      const liveIds = new Set(nextTabs.map((tab) => tab.id));
      for (const previousId of previousById.keys()) {
        if (liveIds.has(previousId)) continue;
        deleteTabRuntime?.(previousId);
        draftStore.delete(previousId);
        statusStore.delete(previousId);
        promptStore.delete(previousId);
      }

      const targetId = typeof payload.activeTabId === 'string'
        ? payload.activeTabId
        : nextTabs[0].id;
      const target = nextTabs.find((tab) => tab.id === targetId) ?? nextTabs[0];
      if (!target) return;
      activeTabIdRef.current = target.id;
      setActiveTabId(target.id);
      if (currentSessionIdRef) {
        currentSessionIdRef.current = target.sessionId;
      }
      setCurrentSessionId?.(target.sessionId);
      setCustomSessionTitle(target.title || null);
    };

    window.onTabResync = resync;
    return () => {
      delete window.onTabResync;
    };
  }, [setCurrentSessionId, setCustomSessionTitle, t, deleteTabRuntime, draftStore, statusStore, promptStore]);

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

      // Refresh every tab's saved status, not just the outgoing one: a
      // background tab may have received deltas since it was last left, and
      // those updates land on the tab object without touching the store.
      for (const tab of tabsRef.current) {
        statusStore.set(tab.id, statusSnapshotOf(tab));
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
      // Background tabs collect prompts in their own fields; save those too so
      // selecting the tab restores the prompt it actually has now.
      for (const tab of tabsRef.current) {
        if (tab.id === currentActive.id) continue;
        promptStore.set(tab.id, {
          permissions: tab.pendingPermissions ?? [],
          questions: tab.pendingQuestions ?? [],
        });
      }
      if (window.__dialogStore) {
        const dialogSnap = window.__dialogStore.getSnapshot();
        promptStore.set(currentActive.id, {
          permissions: dialogSnap.permissions,
          questions: dialogSnap.questions,
        });
        currentActive.pendingPermissions = dialogSnap.permissions;
        currentActive.pendingQuestions = dialogSnap.questions;
      }

      // The tab is leaving the viewport. Its runtime stays with it: the
      // accumulator belongs to the tab, not to the view, so a background turn
      // keeps filling this tab's own runtime.
    }
  }, [chatInputRef, captureScrollState, draftStore, statusStore, promptStore]);

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
      // history is loading). The guard belongs to the tab that started it, so
      // switching to a different tab does not silence this one — but a tab
      // that is asked to show a session other than the one it is switching to
      // abandons its own transition.
      const pendingTransitionTarget = transitionTargetFor(targetTabId);
      if (
        pendingTransitionTarget
        && pendingTransitionTarget !== targetTab.sessionId
      ) {
        endTransition(targetTabId);
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
      // The target tab's runtime is already its own — nothing is copied here.
      // A tab that started streaming in the background has no live accumulator
      // yet, so its streaming flag is recorded on the tab itself.
      const targetStatus = statusStore.get(targetTab.id);
      if (targetStatus?.streamingActive ?? targetTab.streamingActive) {
        setTabRuntimeStreaming?.(targetTab.id, true);
      }
      setLoading?.(targetStatus?.loading ?? targetTab.loading ?? false);
      setStreamingActive?.(targetStatus?.streamingActive ?? targetTab.streamingActive ?? false);
      setIsThinking?.(targetStatus?.isThinking ?? targetTab.isThinking ?? false);
      setStatus?.(targetStatus?.status ?? targetTab.status ?? '');
      setRevertBoundaryId?.(targetStatus ? targetStatus.revertBoundaryId : (targetTab.revertBoundaryId ?? null));
      setIsCompacting?.(targetStatus?.isCompacting ?? targetTab.isCompacting ?? false);
      setCompactingStartTime?.(targetStatus ? targetStatus.compactingStartTime : (targetTab.compactingStartTime ?? null));
      setTaskEvents?.(targetStatus?.taskEvents ?? targetTab.taskEvents ?? {});
      setSseTodos?.(targetStatus ? targetStatus.sseTodos : (targetTab.sseTodos ?? null));
      setSubagentHistories?.(targetStatus?.subagentHistories ?? targetTab.subagentHistories ?? {});
      setFilteredFileChanges?.(targetStatus?.filteredFileChanges ?? targetTab.filteredFileChanges ?? []);

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
      const targetTurnStartedAt = targetStatus ? targetStatus.turnStartedAt : (targetTab.turnStartedAt ?? null);
      setLoadingStartTime?.(targetTurnStartedAt);
      window.__turnStartedAt = targetTurnStartedAt ?? undefined;

      setUsagePercentage?.(targetStatus?.usagePercentage ?? targetTab.usagePercentage ?? 0);
      setUsageUsedTokens?.(targetStatus ? targetStatus.usageUsedTokens : targetTab.usageUsedTokens);
      setUsageMaxTokens?.(targetStatus ? targetStatus.usageMaxTokens : targetTab.usageMaxTokens);

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
        const targetPrompts = promptStore.get(targetTab.id);
        window.__dialogStore.restore(
          targetPrompts?.permissions ?? targetTab.pendingPermissions ?? [],
          targetPrompts?.questions ?? targetTab.pendingQuestions ?? []
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
      setTabRuntimeStreaming,
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
      statusStore,
      promptStore,
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
        sendTabCommand(newId, 'tab.create');
        const newTab: SessionTabData = {
          id: newId,
          sessionId,
          messageStore: new TabMessageStore(),
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
    sendTabCommand(newId, 'tab.create');
    const newTab: SessionTabData = {
      id: newId,
      sessionId: null,
      messageStore: new TabMessageStore(),
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
        sendInterrupt({ tabId, sessionId: closingTab.sessionId });
      }
      if (closingTab?.sessionId) {
        clearSessionSequenceBarrier(closingTab.sessionId);
      }
      // One command closes the tab whether or not it ever got a session.
      sendTabCommand(tabId, 'tab.close');
      // The tab loses its runtime; a recycled tab id must never inherit a dead
      // session's accumulator.
      deleteTabRuntime?.(tabId);
      clearTransition(tabId);
      draftStore.delete(tabId);
      statusStore.delete(tabId);
      promptStore.delete(tabId);

      setTabs((prev) => {
        if (prev.length <= 1) {
          const defaultModel = getFirstPreferredModelId(
            currentProvider || 'opencode',
            availableModels || [],
          ) || '';
          const freshTab: SessionTabData = {
            id: createTabId(),
            sessionId: null,
            messageStore: new TabMessageStore(),
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
          sendTabCommand(freshTab.id, 'tab.create');
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
          // Last tab closed: the dead tab's runtime goes with it, and the fresh
          // tab starts from a clean runtime instead of inheriting the dead
          // session's accumulator / turnId / message index.
          deleteTabRuntime?.(tabId);
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
            // Refresh the sibling from its live fields before reading the
            // stores; background updates since the last switch landed there.
            statusStore.set(nextActive.id, statusSnapshotOf(nextActive));
            promptStore.set(nextActive.id, {
              permissions: nextActive.pendingPermissions ?? [],
              questions: nextActive.pendingQuestions ?? [],
            });
            const nextStatus = statusStore.get(nextActive.id);
            if (nextStatus?.streamingActive ?? nextActive.streamingActive) {
              setTabRuntimeStreaming?.(nextActive.id, true);
            }
            setLoading?.(nextStatus?.loading ?? nextActive.loading ?? false);
            setStreamingActive?.(nextStatus?.streamingActive ?? nextActive.streamingActive ?? false);
            setIsThinking?.(nextStatus?.isThinking ?? nextActive.isThinking ?? false);
            setStatus?.(nextStatus?.status ?? nextActive.status ?? '');
            setRevertBoundaryId?.(nextStatus ? nextStatus.revertBoundaryId : (nextActive.revertBoundaryId ?? null));
            setIsCompacting?.(nextStatus?.isCompacting ?? nextActive.isCompacting ?? false);
            setCompactingStartTime?.(nextStatus ? nextStatus.compactingStartTime : (nextActive.compactingStartTime ?? null));
            setTaskEvents?.(nextStatus?.taskEvents ?? nextActive.taskEvents ?? {});
            setSseTodos?.(nextStatus ? nextStatus.sseTodos : (nextActive.sseTodos ?? null));
            setSubagentHistories?.(nextStatus?.subagentHistories ?? nextActive.subagentHistories ?? {});
            setFilteredFileChanges?.(nextStatus?.filteredFileChanges ?? nextActive.filteredFileChanges ?? []);

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
            const nextTurnStartedAt = nextStatus ? nextStatus.turnStartedAt : (nextActive.turnStartedAt ?? null);
            setLoadingStartTime?.(nextTurnStartedAt);
            window.__turnStartedAt = nextTurnStartedAt ?? undefined;

            setUsagePercentage?.(nextStatus?.usagePercentage ?? nextActive.usagePercentage ?? 0);
            setUsageUsedTokens?.(nextStatus ? nextStatus.usageUsedTokens : nextActive.usageUsedTokens);
            setUsageMaxTokens?.(nextStatus ? nextStatus.usageMaxTokens : nextActive.usageMaxTokens);

            setSessionLoading?.(false);

            if (window.__dialogStore) {
              const nextPrompts = promptStore.get(nextActive.id);
              window.__dialogStore.restore(
                nextPrompts?.permissions ?? nextActive.pendingPermissions ?? [],
                nextPrompts?.questions ?? nextActive.pendingQuestions ?? []
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
      deleteTabRuntime,
      draftStore,
      statusStore,
      promptStore,
      setTabRuntimeStreaming,
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
