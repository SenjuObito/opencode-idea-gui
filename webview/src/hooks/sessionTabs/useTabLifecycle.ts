import { useCallback, useEffect } from 'react';
import type { SessionTabData } from '../../components/ChatHeader/types';
import { createTabId } from '../../store/tabStore';
import { TabMessageStore } from '../../store/tabMessageStore';
import type { TabDraftStore } from '../../store/tabDraftStore';
import type { TabStatusStore } from '../../store/tabStatusStore';
import { statusSnapshotOf } from '../../store/tabStatusStore';
import type { TabPromptStore } from '../../store/tabPromptStore';
import { getFirstPreferredModelId, resolveSessionModel } from '../../components/ChatInputBox/modelSelectUtils';
import { getRecommendedReasoningEffort } from '../../components/ChatInputBox/types';
import { sendHostCommand, sendInterrupt, sendTabCommand } from '../../utils/bridge';
import { clearSessionSequenceBarrier } from '../windowCallbacks/sequenceBarrier';
import { clearTransition } from '../windowCallbacks/tabTransition';
import { clearTabMergeCache } from '../../utils/messageMergeCache';
import type { UseSessionTabsOptions } from './types';
import type { useTabBarState } from './useTabBarState';

export interface UseTabLifecycleOptions extends Pick<
  UseSessionTabsOptions,
  | 'currentProvider'
  | 'availableModels'
  | 'defaultAutoOpenFileEnabled'
  | 't'
  | 'setCurrentSessionId'
  | 'setCustomSessionTitle'
  | 'setMessages'
  | 'restoreScrollState'
  | 'setLoading'
  | 'setStreamingActive'
  | 'setIsThinking'
  | 'setStatus'
  | 'setRevertBoundaryId'
  | 'setIsCompacting'
  | 'setCompactingStartTime'
  | 'setTaskEvents'
  | 'setSseTodos'
  | 'subagentHistories'
  | 'setSubagentHistories'
  | 'setFilteredFileChanges'
  | 'setSelectedModel'
  | 'setPermissionMode'
  | 'setReasoningEffort'
  | 'setContextInfo'
  | 'setLoadingStartTime'
  | 'setUsagePercentage'
  | 'setUsageUsedTokens'
  | 'setUsageMaxTokens'
  | 'setSessionLoading'
  | 'setCurrentView'
  | 'chatInputRef'
  | 'deleteTabRuntime'
  | 'setTabRuntimeStreaming'
  | 'currentSessionIdRef'
> {
  tabBar: ReturnType<typeof useTabBarState>;
  draftStore: TabDraftStore;
  statusStore: TabStatusStore;
  promptStore: TabPromptStore;
  saveCurrentTabSnapshot: () => void;
  selectTab: (tabId: string) => void;
  syncHostActiveTabTo: (tabId: string | null | undefined) => void;
  syncPermissionModeTo: (sessionId: string | null | undefined, mode: string) => void;
}

export function useTabLifecycle(options: UseTabLifecycleOptions) {
  const {
    currentProvider,
    availableModels,
    defaultAutoOpenFileEnabled,
    t,
    setCurrentSessionId,
    setCustomSessionTitle,
    setMessages,
    restoreScrollState,
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
    deleteTabRuntime,
    setTabRuntimeStreaming,
    currentSessionIdRef,
    tabBar,
    draftStore,
    statusStore,
    promptStore,
    saveCurrentTabSnapshot,
    selectTab,
    syncHostActiveTabTo,
    syncPermissionModeTo,
  } = options;

  const {
    tabsRef,
    activeTabIdRef,
    setTabs,
    setActiveTabId,
    closeTabRef,
  } = tabBar;

  const createTab = useCallback(() => {
    saveCurrentTabSnapshot();

    const existingUnbound = tabsRef.current.find((t) => !t.sessionId);
    if (existingUnbound) {
      selectTab(existingUnbound.id);
      return;
    }

    const defaultModel = getFirstPreferredModelId(
      currentProvider || 'opencode',
      availableModels || [],
    ) || '';

    const selectedModelInfo = availableModels?.find(
      (m) => m.id === defaultModel || m.id.endsWith('/' + defaultModel) || defaultModel.endsWith('/' + m.id)
    );
    const defaultEffort = getRecommendedReasoningEffort(selectedModelInfo?.variants) || 'default';

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
      reasoningEffort: defaultEffort,
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

    chatInputRef?.current?.setValue?.('');
    setSessionLoading?.(false);
    setCurrentView?.('chat');

    window.__dialogStore?.clearActive?.();

    sendHostCommand(newId, 'tab.title.set', { title: newTab.title });
    syncHostActiveTabTo(newId);
  }, [
    saveCurrentTabSnapshot,
    tabsRef,
    selectTab,
    currentProvider,
    availableModels,
    t,
    defaultAutoOpenFileEnabled,
    activeTabIdRef,
    setTabs,
    setActiveTabId,
    currentSessionIdRef,
    setCurrentSessionId,
    setCustomSessionTitle,
    setMessages,
    restoreScrollState,
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
    setLoadingStartTime,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    chatInputRef,
    setSessionLoading,
    setCurrentView,
    syncHostActiveTabTo,
  ]);

  useEffect(() => {
    window.__openNewSessionTab = createTab;
    return () => {
      if (window.__openNewSessionTab === createTab) {
        delete window.__openNewSessionTab;
      }
    };
  }, [createTab]);

  const closeTab = useCallback(
    (tabId: string) => {
      const closingTab = tabsRef.current.find((t) => t.id === tabId);
      if (closingTab?.loading && closingTab.sessionId) {
        sendInterrupt({ tabId, sessionId: closingTab.sessionId });
      }
      if (closingTab?.sessionId) {
        clearSessionSequenceBarrier(closingTab.sessionId);
      }
      sendTabCommand(tabId, 'tab.close');
      deleteTabRuntime?.(tabId);
      clearTransition(tabId);
      clearTabMergeCache(tabId);
      draftStore.delete(tabId);
      statusStore.delete(tabId);
      promptStore.delete(tabId);

      setTabs((prev) => {
        if (prev.length <= 1) {
          const defaultModel = getFirstPreferredModelId(
            currentProvider || 'opencode',
            availableModels || [],
          ) || '';
          const selectedModelInfo = availableModels?.find(
            (m) => m.id === defaultModel || m.id.endsWith('/' + defaultModel) || defaultModel.endsWith('/' + m.id)
          );
          const defaultEffort = getRecommendedReasoningEffort(selectedModelInfo?.variants) || 'default';
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
            reasoningEffort: defaultEffort,
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
          deleteTabRuntime?.(tabId);
          clearTabMergeCache(tabId);
          return [freshTab];
        }

        const nextTabs = prev.filter((t) => t.id !== tabId);
        if (tabId === activeTabIdRef.current) {
          const closedIndex = prev.findIndex((t) => t.id === tabId);
          const nextActive = nextTabs[Math.max(0, closedIndex - 1)];
          if (nextActive) {
            activeTabIdRef.current = nextActive.id;
            setActiveTabId(nextActive.id);
            syncHostActiveTabTo(nextActive.id);
            if (currentSessionIdRef) {
              currentSessionIdRef.current = nextActive.sessionId;
            }
            setCurrentSessionId?.(nextActive.sessionId);
            setCustomSessionTitle(nextActive.title || null);
            setMessages(nextActive.messages || []);
            restoreScrollState?.(nextActive.scrollState ?? null);
            statusStore.set(nextActive.id, statusSnapshotOf(nextActive));
            promptStore.set(nextActive.id, {
              permissions: nextActive.pendingPermissions ?? [],
              questions: nextActive.pendingQuestions ?? [],
              plans: nextActive.pendingPlans ?? [],
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
                nextPrompts?.questions ?? nextActive.pendingQuestions ?? [],
                nextPrompts?.plans ?? nextActive.pendingPlans ?? [],
              );
            }
          }
        }
        return nextTabs;
      });
    },
    [
      tabsRef,
      deleteTabRuntime,
      draftStore,
      statusStore,
      promptStore,
      setTabs,
      currentProvider,
      availableModels,
      t,
      defaultAutoOpenFileEnabled,
      setActiveTabId,
      setCurrentSessionId,
      setCustomSessionTitle,
      setMessages,
      restoreScrollState,
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
      setLoadingStartTime,
      setUsagePercentage,
      setUsageUsedTokens,
      setUsageMaxTokens,
      setSessionLoading,
      activeTabIdRef,
      syncHostActiveTabTo,
      currentSessionIdRef,
      setTabRuntimeStreaming,
      syncPermissionModeTo,
      setContextInfo,
    ]
  );

  useEffect(() => {
    closeTabRef.current = closeTab;
  }, [closeTab, closeTabRef]);

  const forkTab = useCallback(
    (tab: SessionTabData) => {
      if (!tab.sessionId) return;
      sendHostCommand(tab.id, 'session.fork', {
        sourceTabId: tab.id,
        targetTabId: createTabId(),
      });
    },
    []
  );

  useEffect(() => {
    (window as any).createSessionTab = createTab;
    return () => {
      delete (window as any).createSessionTab;
    };
  }, [createTab]);

  return {
    createTab,
    closeTab,
    forkTab,
  };
}
