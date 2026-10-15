import { useCallback, useEffect, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage } from '../types';
import type { ContextInfo } from './useWindowCallbacks';
import type { SessionTabData } from '../components/ChatHeader/types';
import type { ModelInfo, PermissionMode, ReasoningEffort } from '../components/ChatInputBox/types';
import { getFirstPreferredModelId, resolveSessionModel } from '../components/ChatInputBox/modelSelectUtils';
import { sendBridgeEvent } from '../utils/bridge';

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
  setSelectedModel?: (model: string) => void;
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
  t: TFunction;
}

export function useSessionTabs({
  currentSessionId,
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
  t,
}: UseSessionTabsOptions) {
  const [tabs, setTabs] = useState<SessionTabData[]>([
    {
      id: 'tab-1',
      sessionId: null,
      title: t('common.newSession', { defaultValue: '新会话' }),
      messages: [],
      isShared: false,
      autoOpenFileEnabled: defaultAutoOpenFileEnabled ?? false,
      permissionMode: permissionMode || 'default',
      selectedModel: selectedModel,
      reasoningEffort: reasoningEffort || 'medium',
      filteredFileChanges: [],
      contextInfo: contextInfo ?? null,
      turnStartedAt: loadingStartTime ?? null,
      usagePercentage: usagePercentage ?? 0,
      usageUsedTokens: usageUsedTokens,
      usageMaxTokens: usageMaxTokens,
    },
  ]);
  const [activeTabId, setActiveTabId] = useState<string>('tab-1');
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
            title: sessionTitle || tab.title,
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
    if (!currentActive) return false;
    return currentActive.sessionId === sessionId || currentActive.id === sessionId;
  }, []);

  const updateBackgroundTab = useCallback((
    predicate: (tab: SessionTabData) => boolean,
    updater: (tab: SessionTabData) => void
  ) => {
    setTabs((prev) =>
      prev.map((tab) => {
        if (predicate(tab)) {
          const clone = { ...tab };
          updater(clone);
          return clone;
        }
        return tab;
      })
    );
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
      setTabs((prev) =>
        prev.map((tab) => {
          if (tab.sessionId === sessionId || tab.id === sessionId) {
            return { ...tab, title: trimmed };
          }
          return tab;
        })
      );
      const cur = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
      if (cur && (cur.sessionId === sessionId || cur.id === sessionId)) {
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
        const cur = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
        return cur?.sessionId ?? null;
      },
      getTabs: () => tabsRef.current,
      isActiveSession,
      updateBackgroundTab,
      updateTabSessionId,
      updateTabTitle,
      closeTabBySessionId,
    };
    return () => {
      delete window.__sessionTabsStore;
    };
  }, [isActiveSession, updateBackgroundTab, updateTabSessionId, updateTabTitle, closeTabBySessionId]);

  // Helper: Save current active tab snapshot using real-time runtimeStateRef
  const saveCurrentTabSnapshot = useCallback(() => {
    const currentActive = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
    if (currentActive) {
      const snap = runtimeStateRef.current;
      currentActive.messages = snap.messages;
      currentActive.sessionId = snap.currentSessionId;
      currentActive.title = snap.sessionTitle || currentActive.title;
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

      const currentDraftText = chatInputRef?.current?.getValue?.() ?? '';
      currentActive.draft = {
        text: currentDraftText,
        attachments: [],
        fileTags: [],
      };
      if (window.__dialogStore) {
        const dialogSnap = window.__dialogStore.getSnapshot();
        currentActive.pendingPermissions = dialogSnap.permissions;
        currentActive.pendingQuestions = dialogSnap.questions;
      }
    }
  }, [chatInputRef]);

  // Select tab (pure view switch: preserves background generation, never interrupts)
  const selectTab = useCallback(
    (targetTabId: string) => {
      if (targetTabId === activeTabIdRef.current) return;

      saveCurrentTabSnapshot();

      const targetTab = tabsRef.current.find((t) => t.id === targetTabId);
      if (!targetTab) return;

      setActiveTabId(targetTabId);

      // Restore target tab's snapshot into active view
      setCurrentSessionId?.(targetTab.sessionId);
      setCustomSessionTitle(targetTab.title || null);
      setMessages(targetTab.messages || []);
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

      // Restore session model & mode & reasoning effort
      const targetMode = targetTab.permissionMode || 'default';
      setPermissionMode?.(targetMode);
      sendBridgeEvent('set_mode', targetMode);

      const targetEffort = targetTab.reasoningEffort || 'medium';
      setReasoningEffort?.(targetEffort);
      sendBridgeEvent('set_reasoning_effort', targetEffort);

      const resolvedModel = resolveSessionModel(
        targetTab.selectedModel,
        currentProvider || 'opencode',
        availableModels || [],
      );
      if (resolvedModel) {
        setSelectedModel?.(resolvedModel);
        sendBridgeEvent('set_model', resolvedModel);
      }

      // Restore contextInfo & autoOpenFileEnabled
      setContextInfo?.(targetTab.contextInfo ?? null);
      sendBridgeEvent(
        'set_auto_open_file_enabled',
        JSON.stringify({ autoOpenFileEnabled: Boolean(targetTab.autoOpenFileEnabled) })
      );

      // Restore turnStartedAt & usage tracking to prevent cross-session pollution
      const targetTurnStartedAt = targetTab.turnStartedAt ?? null;
      setLoadingStartTime?.(targetTurnStartedAt);
      window.__turnStartedAt = targetTurnStartedAt ?? undefined;

      setUsagePercentage?.(targetTab.usagePercentage ?? 0);
      setUsageUsedTokens?.(targetTab.usageUsedTokens);
      setUsageMaxTokens?.(targetTab.usageMaxTokens);

      // Restore input box draft
      if (chatInputRef?.current?.setValue) {
        chatInputRef.current.setValue(targetTab.draft?.text ?? '');
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

      if (targetTab.sessionId) {
        sendBridgeEvent('switch_session', JSON.stringify({ sessionId: targetTab.sessionId }));
      }
    },
    [
      saveCurrentTabSnapshot,
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
      const resolvedEffort = (historyEffort as ReasoningEffort) || 'medium';

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
        setTabs((prev) =>
          prev.map((t) =>
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
                }
              : t
          )
        );
        setCurrentSessionId?.(sessionId);
        setCustomSessionTitle(resolvedTitle);
      } else {
        // Create new tab for this history session
        saveCurrentTabSnapshot();

        const newId = `tab-${Date.now()}`;
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

        setTabs((prev) => [...prev, newTab]);
        setActiveTabId(newId);
        setCurrentSessionId?.(sessionId);
        setCustomSessionTitle(resolvedTitle);
      }

      setMessages([]);
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

      // Restore session model & mode & reasoning effort
      if (resolvedModel) {
        setSelectedModel?.(resolvedModel);
        sendBridgeEvent('set_model', resolvedModel);
      }
      setPermissionMode?.(resolvedMode);
      sendBridgeEvent('set_mode', resolvedMode);
      setReasoningEffort?.(resolvedEffort);
      sendBridgeEvent('set_reasoning_effort', resolvedEffort);
      setContextInfo?.(null);

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
        sendBridgeEvent('load_session', JSON.stringify({ sessionId }));
      }
    },
    [
      selectTab,
      saveCurrentTabSnapshot,
      messages,
      defaultAutoOpenFileEnabled,
      loadHistorySession,
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

    const defaultModel = getFirstPreferredModelId(
      currentProvider || 'opencode',
      availableModels || [],
    ) || '';

    const newId = `tab-${Date.now()}`;
    const newTab: SessionTabData = {
      id: newId,
      sessionId: null,
      title: t('common.newSession', { defaultValue: '新会话' }),
      messages: [],
      isShared: false,
      loading: false,
      streamingActive: false,
      pendingPermissions: [],
      pendingQuestions: [],
      autoOpenFileEnabled: defaultAutoOpenFileEnabled ?? false,
      selectedModel: defaultModel,
      permissionMode: 'default',
      reasoningEffort: 'medium',
      filteredFileChanges: [],
      contextInfo: null,
    };

    setTabs((prev) => [...prev, newTab]);
    setActiveTabId(newId);
    setCurrentSessionId?.(null);
    setCustomSessionTitle(null);
    setMessages([]);
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

    // Set model, mode, effort for brand new tab
    if (defaultModel) {
      setSelectedModel?.(defaultModel);
      sendBridgeEvent('set_model', defaultModel);
    }
    setPermissionMode?.('default');
    sendBridgeEvent('set_mode', 'default');
    setReasoningEffort?.('medium');
    sendBridgeEvent('set_reasoning_effort', 'medium');
    setContextInfo?.(null);
    sendBridgeEvent(
      'set_auto_open_file_enabled',
      JSON.stringify({ autoOpenFileEnabled: Boolean(defaultAutoOpenFileEnabled) })
    );

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
  }, [
    saveCurrentTabSnapshot,
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
  ]);

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
      }

      setTabs((prev) => {
        if (prev.length <= 1) {
          const defaultModel = getFirstPreferredModelId(
            currentProvider || 'opencode',
            availableModels || [],
          ) || '';
          const freshTab: SessionTabData = {
            id: `tab-${Date.now()}`,
            sessionId: null,
            title: t('common.newSession', { defaultValue: '新会话' }),
            messages: [],
            isShared: false,
            autoOpenFileEnabled: defaultAutoOpenFileEnabled ?? false,
            selectedModel: defaultModel,
            permissionMode: 'default',
            reasoningEffort: 'medium',
            filteredFileChanges: [],
            contextInfo: null,
          };
          setActiveTabId(freshTab.id);
          setCurrentSessionId?.(null);
          setCustomSessionTitle(null);
          setMessages([]);
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
          if (defaultModel) {
            setSelectedModel?.(defaultModel);
            sendBridgeEvent('set_model', defaultModel);
          }
          setPermissionMode?.('default');
          sendBridgeEvent('set_mode', 'default');
          setReasoningEffort?.('medium');
          sendBridgeEvent('set_reasoning_effort', 'medium');
          setContextInfo?.(null);
          setLoadingStartTime?.(null);
          window.__turnStartedAt = undefined;
          setUsagePercentage?.(0);
          setUsageUsedTokens?.(undefined);
          setUsageMaxTokens?.(undefined);
          setSessionLoading?.(false);
          return [freshTab];
        }

        const nextTabs = prev.filter((t) => t.id !== tabId);
        if (tabId === activeTabIdRef.current) {
          const closedIndex = prev.findIndex((t) => t.id === tabId);
          const nextActive = nextTabs[Math.max(0, closedIndex - 1)];
          if (nextActive) {
            setActiveTabId(nextActive.id);
            setCurrentSessionId?.(nextActive.sessionId);
            setCustomSessionTitle(nextActive.title || null);
            setMessages(nextActive.messages || []);
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
            sendBridgeEvent('set_mode', nextMode);

            const nextEffort = nextActive.reasoningEffort || 'medium';
            setReasoningEffort?.(nextEffort);
            sendBridgeEvent('set_reasoning_effort', nextEffort);

            const resolvedModel = resolveSessionModel(
              nextActive.selectedModel,
              currentProvider || 'opencode',
              availableModels || [],
            );
            if (resolvedModel) {
              setSelectedModel?.(resolvedModel);
              sendBridgeEvent('set_model', resolvedModel);
            }

            setContextInfo?.(nextActive.contextInfo ?? null);
            sendBridgeEvent(
              'set_auto_open_file_enabled',
              JSON.stringify({ autoOpenFileEnabled: Boolean(nextActive.autoOpenFileEnabled) })
            );

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

  const setActiveTabAutoOpenFileEnabled = useCallback((enabled: boolean) => {
    setTabs((prev) =>
      prev.map((tab) => {
        if (tab.id === activeTabIdRef.current) {
          return { ...tab, autoOpenFileEnabled: enabled };
        }
        return tab;
      })
    );
    sendBridgeEvent('set_auto_open_file_enabled', JSON.stringify({ autoOpenFileEnabled: enabled }));
  }, []);

  return {
    tabs,
    activeTabId,
    selectTab,
    createTab,
    closeTab,
    renameTab,
    forkTab,
    openSessionInTab,
    activeTabAutoOpenFileEnabled,
    setActiveTabAutoOpenFileEnabled,
  };
}
