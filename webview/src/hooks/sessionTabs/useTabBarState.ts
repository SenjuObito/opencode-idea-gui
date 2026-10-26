import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { SessionTabData } from '../../components/ChatHeader/types';
import type { TabType } from '../../components/StatusPanel/types';
import { getTabStore } from '../../store/tabStore';
import { TabMessageStore } from '../../store/tabMessageStore';
import type { TabDraftStore } from '../../store/tabDraftStore';
import { sendHostCommand } from '../../utils/bridge';
import type { UseSessionTabsOptions } from './types';

export interface UseTabBarStateOptions extends Pick<
  UseSessionTabsOptions,
  | 'currentSessionId'
  | 'currentSessionIdRef'
  | 'setCurrentSessionId'
  | 'sessionTitle'
  | 'setCustomSessionTitle'
  | 'updateHistoryTitle'
  | 'revertBoundaryId'
  | 'taskEvents'
  | 'filteredFileChanges'
  | 'permissionMode'
  | 'selectedModel'
  | 'reasoningEffort'
  | 'contextInfo'
  | 'usagePercentage'
  | 'usageUsedTokens'
  | 'usageMaxTokens'
  | 'isCompacting'
  | 'compactingStartTime'
  | 'isShared'
  | 'defaultAutoOpenFileEnabled'
  | 'deleteTabRuntime'
  | 't'
> {
  draftStore: TabDraftStore;
}

export function useTabBarState(options: UseTabBarStateOptions) {
  const {
    currentSessionId,
    currentSessionIdRef,
    setCurrentSessionId,
    sessionTitle,
    setCustomSessionTitle,
    updateHistoryTitle,
    revertBoundaryId,
    taskEvents,
    filteredFileChanges,
    permissionMode,
    selectedModel,
    reasoningEffort,
    contextInfo,
    usagePercentage,
    usageUsedTokens,
    usageMaxTokens,
    isCompacting,
    compactingStartTime,
    isShared,
    defaultAutoOpenFileEnabled,
    deleteTabRuntime,
    t,
    draftStore,
  } = options;

  const tabStore = getTabStore();
  useState(() => {
    if (tabStore.size() === 0) {
      tabStore.setTabs([
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
          turnStartedAt: null,
          usagePercentage: usagePercentage ?? 0,
          usageUsedTokens: usageUsedTokens,
          usageMaxTokens: usageMaxTokens,
        },
      ]);
      tabStore.setActiveTabId('tab-1');
    }
  });

  const tabs = useSyncExternalStore(tabStore.subscribe, tabStore.getTabs, tabStore.getTabs);
  const activeTabId = useSyncExternalStore(
    tabStore.subscribe,
    tabStore.getActiveTabId,
    tabStore.getActiveTabId,
  );
  const setTabs = tabStore.setTabs;
  const setActiveTabId = tabStore.setActiveTabId;

  const tabsRef = useRef(tabs);
  useEffect(() => {
    tabsRef.current = tabs;
  }, [tabs]);

  const activeTabIdRef = useRef(activeTabId);
  useEffect(() => {
    activeTabIdRef.current = activeTabId;
  }, [activeTabId]);

  // Sync active tab's metadata as conversation progresses
  useEffect(() => {
    setTabs((prev) => {
      const active = prev.find((t) => t.id === activeTabId);
      if (!active) return prev;

      const nextSessionId = currentSessionId ?? active.sessionId;
      const nextTitle = (!sessionTitle || sessionTitle === t('common.newSession')) ? active.title : (sessionTitle || active.title);
      const nextUsagePercentage = usagePercentage ?? active.usagePercentage;
      const nextUsageUsedTokens = usageUsedTokens !== undefined ? usageUsedTokens : active.usageUsedTokens;
      const nextUsageMaxTokens = usageMaxTokens !== undefined ? usageMaxTokens : active.usageMaxTokens;
      const nextMessageBaseIndex = typeof window.__messageBaseIndex === 'number' ? window.__messageBaseIndex : active.messageBaseIndex;
      const nextOpencodeListStart = typeof window.__opencodeListStart === 'number' ? window.__opencodeListStart : active.opencodeListStart;
      const nextPrepended = typeof window.__prependedHistoryMessageCount === 'number' ? window.__prependedHistoryMessageCount : active.prependedHistoryMessageCount;
      // A compact belongs to the tab that started it. Mirroring it here keeps
      // the spinner on that tab when the user switches away and back.
      const nextIsCompacting = isCompacting ?? active.isCompacting ?? false;
      const nextCompactingStartTime = compactingStartTime !== undefined
        ? compactingStartTime
        : (active.compactingStartTime ?? null);

      if (
        active.sessionId === nextSessionId &&
        active.title === nextTitle &&
        active.isShared === isShared &&
        active.revertBoundaryId === revertBoundaryId &&
        active.taskEvents === taskEvents &&
        active.filteredFileChanges === filteredFileChanges &&
        active.permissionMode === permissionMode &&
        active.selectedModel === selectedModel &&
        active.reasoningEffort === reasoningEffort &&
        active.contextInfo === contextInfo &&
        active.usagePercentage === nextUsagePercentage &&
        active.usageUsedTokens === nextUsageUsedTokens &&
        active.usageMaxTokens === nextUsageMaxTokens &&
        active.messageBaseIndex === nextMessageBaseIndex &&
        active.opencodeListStart === nextOpencodeListStart &&
        active.prependedHistoryMessageCount === nextPrepended &&
        (active.isCompacting ?? false) === nextIsCompacting &&
        (active.compactingStartTime ?? null) === nextCompactingStartTime
      ) {
        return prev;
      }

      return prev.map((tab) => {
        if (tab.id === activeTabId) {
          return {
            ...tab,
            sessionId: nextSessionId,
            title: nextTitle,
            isShared,
            revertBoundaryId,
            taskEvents,
            filteredFileChanges,
            permissionMode,
            selectedModel,
            reasoningEffort,
            contextInfo,
            usagePercentage: nextUsagePercentage,
            usageUsedTokens: nextUsageUsedTokens,
            usageMaxTokens: nextUsageMaxTokens,
            messageBaseIndex: nextMessageBaseIndex,
            opencodeListStart: nextOpencodeListStart,
            prependedHistoryMessageCount: nextPrepended,
            isCompacting: nextIsCompacting,
            compactingStartTime: nextCompactingStartTime,
          };
        }
        return tab;
      });
    });
  }, [
    activeTabId,
    currentSessionId,
    sessionTitle,
    isShared,
    revertBoundaryId,
    taskEvents,
    filteredFileChanges,
    permissionMode,
    selectedModel,
    reasoningEffort,
    contextInfo,
    usagePercentage,
    usageUsedTokens,
    usageMaxTokens,
    isCompacting,
    compactingStartTime,
  ]);

  const isActiveSession = useCallback((sessionId: string | null | undefined): boolean => {
    if (!sessionId) return false;
    const currentActive = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
    const viewSessionId = currentSessionIdRef?.current;
    if (viewSessionId) {
      if (viewSessionId === sessionId) return true;
      return !!currentActive && currentActive.id === sessionId;
    }
    if (!currentActive) return false;
    if (currentActive.id === sessionId) return true;
    return currentActive.sessionId === sessionId;
  }, [currentSessionIdRef]);

  const updateBackgroundTab = useCallback((
    predicate: (tab: SessionTabData) => boolean,
    updater: (tab: SessionTabData) => void
  ): boolean => {
    const snapshot = tabsRef.current;
    let matchedId: string | null = null;
    for (let i = 0; i < snapshot.length; i++) {
      if (predicate(snapshot[i])) {
        matchedId = snapshot[i].id;
        break;
      }
    }
    if (matchedId === null) {
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
  }, [setTabs]);

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
  }, [setTabs]);

  const updateAllTabs = useCallback((updater: (tab: SessionTabData) => void) => {
    setTabs((prev) => prev.map((tab) => {
      const clone = { ...tab };
      updater(clone);
      return clone;
    }));
  }, [setTabs]);

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
  }, [setTabs, setCurrentSessionId]);

  const updateTabTitle = useCallback(
    (sessionId: string, newTitle: string, tabId?: string | null) => {
      const trimmed = newTitle?.trim();
      if (!trimmed || !sessionId) return;
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
    [setTabs, setCustomSessionTitle]
  );

  const clearStreamingRuntimeFor = useCallback((sessionId: string) => {
    const owner = getTabIdForSession(sessionId);
    if (owner) deleteTabRuntime?.(owner);
  }, [getTabIdForSession, deleteTabRuntime]);

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
  }, [
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
    draftStore,
    currentSessionIdRef,
    defaultAutoOpenFileEnabled,
  ]);

  const renameTab = useCallback(
    (tabId: string, newTitle: string) => {
      setTabs((prev) =>
        prev.map((t) => (t.id === tabId ? { ...t, title: newTitle, isCustomTitle: true } : t))
      );
      sendHostCommand(tabId, 'tab.title.set', { title: newTitle });
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
    [setTabs, currentSessionId, setCustomSessionTitle, updateHistoryTitle]
  );

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
  }, [setTabs]);

  const toggleStatusPanel = useCallback(() => {
    setTabs((prev) => prev.map((item) => (
      item.id === activeTabIdRef.current
        ? { ...item, statusPanelExpanded: !(item.statusPanelExpanded ?? true) }
        : item
    )));
  }, [setTabs]);

  const setActiveTabAutoOpenFileEnabled = useCallback((enabled: boolean) => {
    setTabs((prev) =>
      prev.map((tab) => {
        if (tab.id === activeTabIdRef.current) {
          return { ...tab, autoOpenFileEnabled: enabled };
        }
        return tab;
      })
    );
    sendHostCommand(activeTabIdRef.current, 'context.refresh', { enabled });
  }, [setTabs]);

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
  }, [setTabs]);

  return {
    tabs,
    activeTabId,
    tabsRef,
    activeTabIdRef,
    setTabs,
    setActiveTabId,
    closeTabRef,
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
    renameTab,
    reorderTabs,
    activeTabAutoOpenFileEnabled,
    setActiveTabAutoOpenFileEnabled,
    statusPanelExpanded,
    statusPanelTab,
    setStatusPanelTab,
    toggleStatusPanel,
  };
}
