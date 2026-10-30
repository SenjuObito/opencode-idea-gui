import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import HistoryView from './components/history/HistoryView';
import SettingsView from './components/settings';
import { sendHostCommand, sendSessionCommand, sendWindowCommand } from './utils/bridge';
import { createTabId, getTabStore } from './store/tabStore';
import { copyViaHost } from './utils/copyUtils';
import { registerHostEvents, unregisterHostEvents } from './utils/hostEventRegistry';
import { preloadSlashCommands } from './components/ChatInputBox/providers';
import {
  useScrollBehavior,
  useSessionManagement,
  useStreamingMessages,
  useWindowCallbacks,
  useHistoryLoader,
  useMessageQueue,
  useThemeInit,
  useContextActions,
  useMessageProcessing,
  useMessageSender,
  useModelProviderState,
  useChatComputations,
  useCompactConfirm,
  useSessionTabs,
} from './hooks';
import { convertMessagesToMarkdown, downloadMarkdown } from './utils/exportMarkdown';
import {
  NEW_SESSION_COMMANDS,
  RESUME_COMMANDS,
  PLAN_COMMANDS,
  CONTEXT_COMMANDS,
  BUILTIN_SESSION_COMMANDS,
} from './hooks/useMessageSender';
import { applyDiffTheme, getStoredDiffTheme } from './utils/diffTheme';
import { collectTaskEventsFromMessages } from './utils/taskNotificationMessage';
import { createCompactSuccessNotice, createCompactFailureNotice, applyMessagesRemoved } from './utils/messageUtils';
import { parseRevertStateEvent } from './utils/revertStateEvent';
import type { HostEventEnvelope } from './utils/hostEventTypes';
import type { CompactNoticeData } from './components/ChatHeader/types';
import type { ClaudeMessage } from './types';
import { getRecommendedReasoningEffort, type Attachment, type ChatInputBoxHandle } from './components/ChatInputBox/types';
import { ToastContainer } from './components/Toast';
import { ChatHeader } from './components/ChatHeader';
import { ChatScreen } from './components/ChatScreen';
import type { MessageListRevealHandle } from './components/ConversationSearch/types';
import { useSubagentContextValues, useSetTaskEvents, useTaskEvents } from './contexts/SubagentContext';
import { useMessages } from './contexts/MessagesContext';
import { useSession } from './contexts/SessionContext';
import { useUIState } from './contexts/UIStateContext';
import { useDialogs } from './contexts/DialogContext';
import { AppDialogs } from './components/AppDialogs';
import ConfirmDialog from './components/ConfirmDialog';
import { DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS } from './utils/permissionDialogTimeout';
import { useCliModels } from './hooks/providers/useCliModels';
import { getFirstPreferredModelId } from './components/ChatInputBox/modelSelectUtils';
import { resolveProviderModels } from './components/ChatInputBox/resolveProviderModels';

const App = () => {
  const { t } = useTranslation();

  // ── Dialog management (extracted to DialogContext, stage 4 of TASK-P1-01) ──
  // Open* / set* are still needed by hooks (useWindowCallbacks).
  // Display state (permissionDialogOpen / askUserQuestionDialogOpen / etc.) is
  // consumed directly inside <AppDialogs> via useDialogs().
  const {
    openPermissionDialog,
    openAskUserQuestionDialog,
    openPlanApprovalDialog,
    forceClosePermissionDialog,
    forceCloseAskUserQuestionDialog,
    invalidateQuestionCard,
    invalidatePermissionCard,
    forceClosePlanApprovalDialog,
    openContextUsageDialog,
    updateContextUsageData,
    closeContextUsageDialog,
  } = useDialogs();

  // ── Messages flow state (extracted to MessagesContext, stage 1 of TASK-P1-01) ──
  // Display state (loadingStartTime / isThinking) is consumed inside <ChatScreen>.
  const {
    messages, setMessages,
    subagentHistories, setSubagentHistories,
    status, setStatus,
    loading, setLoading, loadingStartTime, setLoadingStartTime,
    isThinking, setIsThinking,
    streamingActive, setStreamingActive,
    sessionLoading, setSessionLoading,
    setSseTodos,
    sseTodos,
    isCompacting, setIsCompacting,
    compactingStartTime, setCompactingStartTime,
    compactNotice, setCompactNotice,
  } = useMessages();

  // task_events live in TaskEventProvider (SubagentContext) so their updates do
  // not re-render every MessagesContext consumer.
  const taskEvents = useTaskEvents();
  const setTaskEvents = useSetTaskEvents();

  // ── Session state (extracted to SessionContext, stage 2 of TASK-P1-01) ──
  const {
    currentSessionId, setCurrentSessionId,
    customSessionTitle, setCustomSessionTitle,
    historyData, setHistoryData,
    currentSessionIdRef, customSessionTitleRef,
  } = useSession();

  // ── UI state (extracted to UIStateContext, stage 3 of TASK-P1-01) ──
  // Dialog visibility (addModelDialog / changelog) is consumed inside AppDialogs.
  const {
    currentView, setCurrentView,
    settingsInitialTab, setSettingsInitialTab,
    toasts, addToast, dismissToast, clearToasts,
    contextInfo,
    setContextInfo,
    searchOpen, setSearchOpen,
  } = useUIState();

  // ── Permission dialog timeout (synced with backend config) ──
  const [permissionDialogTimeoutSeconds, setPermissionDialogTimeoutSeconds] = useState(DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS);

  // ── Local refs (don't trigger re-render, kept in App.tsx) ──
  const isFirstMountRef = useRef(true);
  const chatInputRef = useRef<ChatInputBoxHandle>(null);

  // Message anchor node registry for anchor rail navigation
  const messageNodeMapRef = useRef<Map<string, HTMLDivElement>>(new Map());
  const [anchorCollapsedCount, setAnchorCollapsedCount] = useState(0);
  const handleMessageNodeRef = useCallback((id: string, node: HTMLDivElement | null) => {
    if (node) { messageNodeMapRef.current.set(id, node); }
    else { messageNodeMapRef.current.delete(id); }
  }, []);

  const openSessionInTabRef = useRef<(
    sessionId: string,
    title?: string,
    historyModel?: string,
    historyMode?: string,
    historyEffort?: string,
    reservedTabId?: string
  ) => void>(() => {});
  const appTabsRef = useRef<any[]>([]);

  // Imperative handle for the in-page search panel to expand collapsed earlier messages.
  const messageListRef = useRef<MessageListRevealHandle | null>(null);

  // ── Theme & context actions ──
  useThemeInit();
  useContextActions();

  // Apply diff theme on app startup so diff styles work before opening Settings.
  useEffect(() => {
    const ideTheme = window.__INITIAL_IDE_THEME__ ?? null;
    applyDiffTheme(getStoredDiffTheme(), ideTheme);
  }, []);

  // ── Scroll behavior ──
  const {
    messagesContainerRef, messagesEndRef, inputAreaRef,
    isUserAtBottomRef, isAutoScrollingRef, userPausedRef, scrollToBottom,
    captureScrollState, restoreScrollState,
  } = useScrollBehavior({ currentView, messages, loading, isCompacting, streamingActive });

  // ── Streaming messages ──
  const {
    streamingContentRef, streamingThinkingRef, isStreamingRef, useBackendStreamingRenderRef,
    streamingMessageIndexRef, contentUpdateTimeoutRef, thinkingUpdateTimeoutRef,
    lastContentUpdateRef, lastThinkingUpdateRef, autoExpandedThinkingKeysRef,
    streamingTurnIdRef, turnIdCounterRef,
    findLastAssistantIndex, extractRawBlocks,
    getOrCreateStreamingAssistantIndex, patchAssistantForStreaming,
    deleteRuntimeForTab, setRuntimeStreamingForTab,
  } = useStreamingMessages();

  // (Toast helpers moved to UIStateContext)

  // ── Model/Provider state ──
  const {
    currentProvider, selectedModel, permissionMode,
    daemonStatusLoaded, retryDaemonStatus, currentSdkInstalled,
    currentProviderRef,
    activeProviderConfig,
    reasoningEffort, sendShortcut, autoOpenFileEnabled,
    usagePercentage, usageUsedTokens, usageMaxTokens,
    setPermissionMode, setCurrentProvider,
    setOpenCodePermissionMode,
    setSelectedOpenCodeModel,
    setReasoningEffort,
    setSendShortcut, setAutoOpenFileEnabled,
    setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    handleModeSelect, handleModelSelect,
    handleReasoningChange, handleToggleThinking,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
  } = useModelProviderState({ addToast, t, currentSessionId, currentSessionIdRef });

  // ── Global drag event interception ──
  useEffect(() => {
    const preventExternalDrop = (e: DragEvent) => {
      const types = Array.from(e.dataTransfer?.types ?? []);
      const isExternalDrop = types.includes('Files') || types.includes('text/uri-list');
      if (!isExternalDrop) return;
      e.preventDefault();
      e.stopPropagation();
    };
    document.addEventListener('dragover', preventExternalDrop);
    document.addEventListener('drop', preventExternalDrop);
    document.addEventListener('dragenter', preventExternalDrop);
    return () => {
      document.removeEventListener('dragover', preventExternalDrop);
      document.removeEventListener('drop', preventExternalDrop);
      document.removeEventListener('dragenter', preventExternalDrop);
    };
  }, []);

  // ── Close in-conversation search panel when navigating away from chat ──
  // Split from the hotkey effect below so that toggling `searchOpen` does
  // NOT rebind the global keydown listener every time the panel opens/closes.
  useEffect(() => {
    if (currentView !== 'chat' && searchOpen) {
      setSearchOpen(false);
    }
  }, [currentView, searchOpen, setSearchOpen]);

  // ── In-conversation search hotkey (Cmd+F on macOS, Ctrl+F elsewhere) ──
  // Only active in chat view. Settings / history use their own search
  // (HistoryFilters) or none at all — we deliberately let the platform
  // handle Cmd+F there.
  //
  // We deliberately listen for ONLY the platform-appropriate modifier:
  // macOS users use Ctrl+F as the Emacs-style "forward-char" cursor move,
  // so we MUST NOT capture Ctrl+F on macOS. This is a real regression
  // surfaced by code review.
  //
  // Platform detection prefers `navigator.userAgentData.platform` (modern,
  // non-deprecated) and falls back to `userAgent` string sniffing for
  // JCEF / older Chromium where userAgentData may be unavailable.
  // `navigator.platform` is intentionally NOT used — it is deprecated and
  // returns inconsistent values inside JCEF.
  useEffect(() => {
    if (currentView !== 'chat') return;
    const isMac = (() => {
      if (typeof navigator === 'undefined') return false;
      const uaData = (navigator as Navigator & {
        userAgentData?: { platform?: string };
      }).userAgentData;
      const platform = uaData?.platform ?? navigator.userAgent ?? '';
      return /mac|iphone|ipad|ipod/i.test(platform);
    })();
    const handler = (e: KeyboardEvent) => {
      const key = e.key;
      if (key !== 'f' && key !== 'F') return;
      const isFind = isMac ? (e.metaKey && !e.ctrlKey) : (e.ctrlKey && !e.metaKey);
      if (!isFind) return;
      // Don't fight IME composition.
      if (e.isComposing) return;
      e.preventDefault();
      e.stopPropagation();
      setSearchOpen(true);
    };
    document.addEventListener('keydown', handler, true);
    return () => document.removeEventListener('keydown', handler, true);
    // setSearchOpen is a stable useState setter; intentionally omitted from
    // deps so we don't rebind the global listener on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentView]);

  // ── Slash command preloading ──
  useEffect(() => {
    preloadSlashCommands();
  }, []);

  useEffect(() => {
    if (isFirstMountRef.current) { isFirstMountRef.current = false; return; }
  }, [currentView]);

  // Recover task events from task-notification user messages. Recent Claude Code
  // delivers a background agent's terminal report as a plain user message (XML
  // in content) instead of an SDK task_notification event, so history replay —
  // and any live session that never fired the SDK path — would otherwise leave
  // the subagent card stuck on the launch ack text. Derived entries only fill
  // gaps: a real SDK event already in the map is kept as-is.
  // Messages update immutably, so unchanged messages keep their object identity;
  // tracking scanned objects avoids re-scanning the whole conversation on every
  // streaming chunk.
  const scannedTaskNotificationMessagesRef = useRef(new WeakSet<ClaudeMessage>());
  useEffect(() => {
    const scanned = scannedTaskNotificationMessagesRef.current;
    const fresh = messages.filter((m) => !scanned.has(m));
    if (fresh.length === 0) return;
    for (const m of fresh) scanned.add(m);
    const derived = collectTaskEventsFromMessages(fresh);
    if (Object.keys(derived).length === 0) return;
    setTaskEvents((prev) => {
      let changed = false;
      const next = { ...prev };
      for (const [id, event] of Object.entries(derived)) {
        if (next[id]) continue;
        next[id] = event;
        changed = true;
      }
      return changed ? next : prev;
    });
  }, [messages, setTaskEvents]);

  const { cliModels, cliDefaultModel, cliCatalogHasEntries } = useCliModels(currentProvider);

  const availableModels = useMemo(() => {
    return resolveProviderModels({
      provider: currentProvider,
      cliModels,
      cliCatalogHasEntries,
    });
  }, [currentProvider, cliModels, cliCatalogHasEntries]);

  /**
   * A user-chosen model carries its own variant catalog. Carry the previous
   * model's effort over only when it is still meaningful; otherwise use the
   * new model's recommended level. In particular this prevents a `none`
   * variant from surviving a model switch and becoming the visible default.
   * A tab restore (`silent`) keeps that tab's saved effort.
   */
  const handleModelSelectWithEffort = useCallback((modelId: string, options?: { silent?: boolean }) => {
    const modelChanged = modelId !== selectedModel;
    handleModelSelect(modelId, options);
    if (options?.silent || !modelChanged) return;

    const modelInfo = availableModels.find((model) => model.id === modelId);
    const recommended = getRecommendedReasoningEffort(modelInfo?.variants);
    if (recommended && recommended !== reasoningEffort) {
      handleReasoningChange(recommended);
    }
  }, [handleModelSelect, handleReasoningChange, availableModels, selectedModel, reasoningEffort]);

  const resetToPreferredModel = useCallback(() => {
    const preferredId = getFirstPreferredModelId(currentProvider, availableModels, cliDefaultModel);
    if (preferredId) {
      handleModelSelectWithEffort(preferredId);
    }
  }, [currentProvider, availableModels, cliDefaultModel, handleModelSelectWithEffort]);

  // When models catalog arrives on initial load in a new session, immediately select the preferred model.
  const hasAutoSelectedOnStartupRef = useRef(false);
  useEffect(() => {
    if (hasAutoSelectedOnStartupRef.current) return;
    if (!cliCatalogHasEntries || !cliModels.length) return;
    if (currentSessionId === null && messages.length === 0) {
      hasAutoSelectedOnStartupRef.current = true;
      resetToPreferredModel();
    }
  }, [cliCatalogHasEntries, cliModels, currentSessionId, messages.length, resetToPreferredModel]);

  // ── Session management ──
  const {
    showInterruptConfirm,
    suppressNextStatusToastRef,
    forceCreateNewSession,
    handleConfirmInterrupt, handleCancelInterrupt,
    loadHistorySession, deleteHistorySession, deleteHistorySessions, exportHistorySession,
    toggleFavoriteSession, updateHistoryTitle, applyHistoryTitleLocal,
  } = useSessionManagement({
    messages, loading, historyData, currentSessionId, currentSessionIdRef, currentProvider,
    setHistoryData, setMessages, setCurrentView, setCurrentSessionId,
    setCustomSessionTitle, setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    setStatus, setLoading, setIsThinking, setStreamingActive, setSessionLoading,
    setTaskEvents,
    setSseTodos,
    setSubagentHistories,
    setIsCompacting,
    setCompactingStartTime,
    onResetToPreferredModel: resetToPreferredModel,
    clearToasts, addToast, t,
  });

  useHistoryLoader({ currentView, currentProvider });

  const handleRetryHistory = useCallback(() => {
    setHistoryData(null);
    sendWindowCommand('history.refresh');
  }, [currentProvider, setHistoryData]);

  // ── Share state ──
  const [isShared, setIsShared] = useState(false);
  const [sharePending, setSharePending] = useState(false);
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  /** Set once the server reports sharing is disabled ("share": "disabled") — hides the header controls. */
  const [shareHidden, setShareHidden] = useState(false);

  const resetShareState = useCallback(() => {
    setIsShared(false);
    setSharePending(false);
    setShareUrl(null);
    setShareHidden(false);
  }, []);

  /**
   * The session to share is named by the caller when it comes from a tab menu:
   * selecting the tab and sharing it are two separate state updates, so reading
   * the active session here would share the tab the user just left.
   */
  const handleShare = useCallback(async (sessionIdArg?: string | null) => {
    // A tab menu names the tab it opened for. `null` means that tab has no
    // session yet: sharing must not fall back to whichever session is active.
    const sessionId = sessionIdArg === undefined ? currentSessionIdRef.current : sessionIdArg;
    if (!sessionId || sharePending || shareHidden) return;
    // No premature success toast here: the share URL only becomes copyable
    // when the host round-trips onShareSuccess. Feedback happens there.
    setSharePending(true);
    sendSessionCommand('session.share', sessionId);
  }, [sharePending, shareHidden, currentSessionIdRef]);

  const handleUnshare = useCallback(async (sessionIdArg?: string | null) => {
    const sessionId = sessionIdArg === undefined ? currentSessionIdRef.current : sessionIdArg;
    if (!sessionId) return;
    sendSessionCommand('session.unshare', sessionId);
    const tabStore = getTabStore();
    const tabId = tabStore.findIdBySession(sessionId);
    if (tabId) {
      tabStore.updateTab(tabId, (tab) => ({ ...tab, isShared: false }));
      if (tabStore.isActiveTab(tabId)) {
        setIsShared(false);
        setShareUrl(null);
      }
    }
  }, [currentSessionIdRef]);

  const handleCopyShareLink = useCallback(() => {
    if (!shareUrl) {
      // Link unknown (e.g. page reloaded) — re-create the share instead.
      void handleShare();
      return;
    }
    void copyViaHost(shareUrl).then((ok) => {
      addToast(ok ? t('chat.shareSuccess') : t('chat.shareFailed'), ok ? 'success' : 'error');
    });
  }, [shareUrl, handleShare, addToast, t]);

  // ── Undo/Redo state ──
  /** opencode message id of the revert boundary (drives RevertPlaceholderBar). */
  const [revertBoundaryId, setRevertBoundaryId] = useState<string | null>(null);
  const hasRevertStateRef = useRef(false);
  const revertBoundaryIdRef = useRef<string | null>(null);
  const lastLoadedRevertStateRef = useRef<string>('');

  /**
   * 同步更新 revert 状态的 ref 与 boundary（ref 供回调内做变更检测，避开闭包旧值）。
   * messageId 是服务端 revert 指针指向的用户消息 id，占位条据此定位切片。
   */
  const applyRevertState = useCallback((next: boolean, messageId?: string | null) => {
    hasRevertStateRef.current = next;
    revertBoundaryIdRef.current = next ? (messageId ?? null) : null;
    if (typeof window !== 'undefined') {
      window.__hasActiveRevert = next;
    }
    setRevertBoundaryId(next ? (messageId ?? null) : null);
    userPausedRef.current = false;
    isUserAtBottomRef.current = true;
    scrollToBottom();
    requestAnimationFrame(() => {
      scrollToBottom();
    });
  }, [scrollToBottom, userPausedRef, isUserAtBottomRef]);

  const handleSetRevertBoundaryId = useCallback((id: string | null) => {
    applyRevertState(Boolean(id), id);
  }, [applyRevertState]);

  /**
   * Resolve an opencode message id from a chat message. Live messages may
   * carry it top-level or inside raw.id / raw.uuid; history-restored messages
   * always have raw.id (see SdkMessageConverter). Returns undefined when the
   * caller should fall back to the backend's latest-user-message resolution.
   */
  const getMessageId = useCallback((message: ClaudeMessage | null | undefined): string | undefined => {
    if (!message) return undefined;
    if (typeof message.id === 'string' && message.id) return message.id;
    const raw = message.raw as Record<string, unknown> | undefined;
    if (raw && typeof raw === 'object') {
      if (typeof raw.id === 'string' && raw.id) return raw.id;
      if (typeof raw.uuid === 'string' && raw.uuid) return raw.uuid;
      const info = raw.info as Record<string, unknown> | undefined;
      if (info && typeof info === 'object' && typeof info.id === 'string' && info.id) return info.id;
      const msg = raw.message as Record<string, unknown> | undefined;
      if (msg && typeof msg === 'object' && typeof msg.id === 'string' && msg.id) return msg.id;
    }
    return undefined;
  }, []);

  const extractPromptText = useCallback((message: ClaudeMessage | null | undefined): string => {
    if (!message) return '';
    let target = message;
    const id = getMessageId(message);
    if (!target.content && messages.length > 0) {
      if (id && id !== 'latest') {
        target = messages.find((m) => getMessageId(m) === id) || target;
      } else {
        target = [...messages].reverse().find((m) => m.type === 'user' && !m.content?.includes('[tool_result]')) || target;
      }
    }
    let text = target.content || '';
    if (!text && target.raw) {
      const raw = target.raw as Record<string, unknown>;
      if (typeof raw === 'string') {
        text = raw;
      } else if (typeof raw.content === 'string') {
        text = raw.content;
      } else if (Array.isArray(raw.content)) {
        text = (raw.content as any[])
          .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
          .map((b) => b.text)
          .join('\n');
      } else if (raw.message && typeof raw.message === 'object') {
        const inner = raw.message as Record<string, unknown>;
        if (typeof inner.content === 'string') {
          text = inner.content;
        }
      }
    }
    return text.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
  }, [messages, getMessageId]);

  /**
   * Commits the staged rollback when user submits a new prompt or executes a command:
   * Truncates message list, clears revert boundary, and removes placeholder bar.
   */
  const consumeRevertBoundary = useCallback(() => {
    const boundaryId = revertBoundaryIdRef.current;
    if (!hasRevertStateRef.current && !boundaryId) return;
    let idx = -1;
    if (boundaryId && boundaryId !== 'latest') {
      idx = messages.findIndex((m) => getMessageId(m) === boundaryId);
    }
    if (idx < 0) {
      for (let i = messages.length - 1; i >= 0; i--) {
        const m = messages[i];
        if (m.type === 'user' && !m.content?.includes('[tool_result]')) {
          idx = i;
          break;
        }
      }
    }
    if (idx >= 0) {
      const remainingMessages = messages.slice(0, idx);
      setMessages(remainingMessages);
      const activeTabId = getTabStore().getActiveTabId();
      if (activeTabId) {
        getTabStore().updateTab(activeTabId, (tab) => {
          tab.messages = remainingMessages;
          tab.revertBoundaryId = null;
        });
      }
    }
    applyRevertState(false);
  }, [messages, getMessageId, applyRevertState, setMessages]);

  /**
   * Send the revert/unrevert bridge event. Caller must have settled any
   * busy-session confirmation beforehand (see pendingRevert flow).
   */
  const dispatchRevert = useCallback((message: ClaudeMessage) => {
    const id = getMessageId(message);
    const sid = currentSessionIdRef.current;

    // 回填被撤销消息的 prompt 到输入框供用户编辑
    const promptText = extractPromptText(message);
    if (promptText && chatInputRef.current?.setValue) {
      chatInputRef.current.setValue(promptText);
      chatInputRef.current.focus?.();
    }

    if (!id) {
      // live 消息尚未回填 opencode id —— 让宿主通过 listMessages 解析最后一条用户消息
      console.warn('[App] undo: message has no id; falling back to backend latest-user resolution');
      sendSessionCommand('session.revert', sid, { messageId: 'latest' });
      applyRevertState(true, 'latest');
      return;
    }
    console.debug('[App] undo: reverting to message', id);
    sendSessionCommand('session.revert', sid, { messageId: id });
    // Optimistic: boundary is the undone user message; the host's
    // onRevertStateUpdate push carries the authoritative pointer.
    applyRevertState(true, id);
  }, [applyRevertState, getMessageId, extractPromptText]);

  /** Pending "interrupt then undo/redo" confirmation (busy session). */
  const [pendingRevert, setPendingRevert] = useState<
    { op: 'undo'; target: ClaudeMessage } | { op: 'redo' } | null
  >(null);

  const handleUndoMessage = useCallback((message: ClaudeMessage) => {
    if (streamingActive) {
      // 运行中撤销：先确认中断（服务端 revert 在 session busy 时会被拒绝）
      setPendingRevert({ op: 'undo', target: message });
      return;
    }
    dispatchRevert(message);
  }, [dispatchRevert, streamingActive]);

  const handleRedoMessage = useCallback(() => {
    if (streamingActive) {
      setPendingRevert({ op: 'redo' });
      return;
    }
    const sid = currentSessionIdRef.current;
    sendSessionCommand('session.unrevert', sid, {});
    applyRevertState(false);
  }, [applyRevertState, streamingActive]);

  // Confirm/cancel handlers live below, after `interruptSession` (useMessageSender)
  // exists — see handleConfirmInterruptRevert.

  const handleForkRequest = useCallback((message: ClaudeMessage) => {
    if (streamingActive) {
      addToast(t('chat.forkDisabledTooltip'), 'warning');
      return;
    }
    consumeRevertBoundary();
    const id = getMessageId(message);
    const sid = currentSessionIdRef.current;
    // The fork source is named explicitly: the host refuses to fork "the
    // session on screen" when the caller did not say which one it meant.
    const sourceTabId = getTabStore().findIdBySession(sid)
      ?? getTabStore().getActiveTabId()
      ?? '';
    const targetTabId = createTabId();
    if (!id) {
      console.warn('[App] fork: message has no id; falling back to backend latest-user resolution');
      sendHostCommand(sourceTabId, 'session.fork', { sourceTabId, targetTabId, messageId: 'latest' });
      return;
    }
    sendHostCommand(sourceTabId, 'session.fork', { sourceTabId, targetTabId, messageId: id });
  }, [streamingActive, addToast, t, getMessageId, consumeRevertBoundary]);

  /** 全量 fork（Header 按钮 + 斜杠 /fork）：复制整个会话，不带 messageID。 */
  const handleForkFull = useCallback(() => {
    if (streamingActive) {
      addToast(t('chat.forkDisabledTooltip'), 'warning');
      return;
    }
    consumeRevertBoundary();
    const sid = currentSessionIdRef.current;
    const sourceTabId = getTabStore().findIdBySession(sid)
      ?? getTabStore().getActiveTabId()
      ?? '';
    const targetTabId = createTabId();
    sendHostCommand(sourceTabId, 'session.fork', { sourceTabId, targetTabId });
  }, [streamingActive, addToast, t, consumeRevertBoundary]);

  // Latest user message id, used by the builtin /undo slash command.
  const findLatestUserMessageId = useCallback((): string | undefined => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.type !== 'user') continue;
      const id = getMessageId(m);
      if (id) return id;
    }
    return undefined;
  }, [messages, getMessageId]);

  /**
   * Record the compact on the tab that started it.
   *
   * <p>The spinner is tab state, not window state: switching to another tab
   * must not hide it, and switching back must show it again.</p>
   */
  const setTabCompacting = useCallback((tabId: string | null | undefined, compacting: boolean, startedAt: number | null) => {
    if (!tabId) return;
    getTabStore().updateTab(tabId, (tab) => {
      tab.isCompacting = compacting;
      tab.compactingStartTime = startedAt;
    });
  }, []);

  /**
   * Store a compact result on the tab that owns the session.
   *
   * <p>A background tab has no visible transcript to append to, and a
   * foreground tab loses an appended message when the history reload that
   * follows the compaction replaces the transcript. Writing the result on the
   * tab keeps one source of truth: the notice is rendered while the tab is
   * active and cleared by the next user message.</p>
   */
  const applyCompactNotice = useCallback((tabId: string | null | undefined, notice: CompactNoticeData) => {
    const merge = (prev: CompactNoticeData | null | undefined): CompactNoticeData => (
      prev && prev.status === 'failure' && notice.status === 'failure' && !prev.detail && notice.detail
        ? { ...prev, detail: notice.detail }
        : notice
    );
    if (tabId) {
      getTabStore().updateTab(tabId, (tab) => {
        tab.isCompacting = false;
        tab.compactingStartTime = null;
        tab.compactNotice = merge(tab.compactNotice);
      });
    } else {
      // No tab (single-window fallback): keep the notice on the active one. A
      // known tab that disappeared meanwhile drops the result instead of
      // showing it in whichever conversation happens to be on screen.
      setCompactNotice((prev) => merge(prev));
    }
  }, [setCompactNotice]);

  // Register bridge callbacks for share / fork / revert / compact results
  useEffect(() => {    /**
   * A fork that the host bound to a reserved tab.
   *
   * <p>The webview reserved the tab before the daemon answered, so the fork
   * lands in exactly that tab and the source tab stays untouched.</p>
   */
  const onForkBound = (targetTabId: string, sessionId: string, sourceTabId?: string, sourceSessionId?: string) => {
    try {
      if (!targetTabId || !sessionId) return;
      const activeId = getTabStore().getActiveTabId() ?? activeTabId;
      const currentTabs = appTabsRef.current || [];
      const sourceTab =
        (sourceTabId && currentTabs.find((t) => t.id === sourceTabId)) ||
        (sourceSessionId && currentTabs.find((t) => t.sessionId === sourceSessionId)) ||
        (activeId && currentTabs.find((t) => t.id === activeId && t.id !== targetTabId)) ||
        currentTabs.find((t) => t.id !== targetTabId);
      const forkedTitle = sourceTab ? `${sourceTab.title} (Fork)` : `${sessionTitle || 'Session'} (Fork)`;
      openSessionInTabRef.current(sessionId, forkedTitle, undefined, undefined, undefined, targetTabId);
      addToast(t('chat.forkCreatedToast', { defaultValue: '会话已成功 Fork 并打开新标签页' }), 'success');
    } catch (error) {
      console.error('[App] onForkBound failed', error);
    }
  };

  const onForkSuccess = (json: string) => {
      try {
        const payload = JSON.parse(json) as { sessionId?: string; sourceSessionId?: string };
        console.debug('[App] onForkSuccess', payload);
        const newSessionId = payload.sessionId;
        if (!newSessionId) return;
        const sourceTab = appTabsRef.current?.find((t) => t.sessionId === payload.sourceSessionId);
        const forkedTitle = sourceTab ? `${sourceTab.title} (Fork)` : `${sessionTitle || 'Session'} (Fork)`;
        openSessionInTabRef.current(newSessionId, forkedTitle);
        void loadHistorySession(newSessionId);
        addToast(t('chat.forkCreatedToast', { defaultValue: '会话已成功 Fork 并打开新标签页' }), 'success');
      } catch {
        // ignore malformed payloads
      }
    };
    const onForkError = (detail?: string) => {
      console.warn('[App] onForkError', detail);
      addToast(`${t('chat.forkFailed')}${detail ? `: ${detail}` : ''}`, 'error');
    };
    const onShareSuccess = (url: string, env?: HostEventEnvelope) => {
      if (!url) {
        return;
      }
      console.debug('[App] onShareSuccess, copying url to clipboard');
      setSharePending(false);
      // Share state belongs to the tab that was shared. Marking the window and
      // letting a mirror copy it onto the active tab marked the wrong tab when
      // the share came from a background tab's menu.
      const tabStore = getTabStore();
      const tabId = env?.tabId ?? (env?.sessionId ? tabStore.findIdBySession(env.sessionId) : null);
      if (tabId) {
        tabStore.updateTab(tabId, (tab) => ({ ...tab, isShared: true }));
      }
      if (!tabId || tabStore.isActiveTab(tabId)) {
        setIsShared(true);
        setShareUrl(url);
      }
      void copyViaHost(url).then((ok) => {
        if (ok) {
          addToast(t('chat.shareSuccess'), 'success');
        } else {
          // 剪贴板写入失败 ≠ 分享失败：链接已在服务端创建，
          // 提供重试入口而不是误导性的「分享未完成」。
          addToast(t('chat.shareLinkCreated'), 'info', {
            label: t('chat.retryCopy'),
            onClick: () => {
              void copyViaHost(url).then((retryOk) => {
                addToast(retryOk ? t('chat.shareSuccess') : t('chat.shareFailed'), retryOk ? 'success' : 'error');
              });
            },
          }, { duration: 5000 });
        }
      });
    };
    const onShareError = (detail?: string) => {
      console.warn('[App] onShareError', detail);
      setSharePending(false);
      setIsShared(false);
      const reason = (detail ?? '').trim();
      // opencode 在 share 功能被关闭（opencode.json "share": "disabled"）时，
      // 服务端对 share 接口返回 500 InternalServerError —— 映射为可操作的提示。
      if (/InternalServerError|UnknownError/i.test(reason)) {
        setShareHidden(true);
        addToast(`${t('chat.shareFailed')} — ${t('chat.shareDisabledHint')}`, 'error');
        return;
      }
      addToast(reason ? `${t('chat.shareFailed')}: ${reason}` : t('chat.shareFailed'), 'error');
    };
    const onUnshareSuccess = () => {
      console.debug('[App] onUnshareSuccess');
      setSharePending(false);
      setIsShared(false);
      setShareUrl(null);
      addToast(t('chat.unshareSuccess', { defaultValue: '已取消分享会话' }), 'info');
    };
    const onUnshareError = (detail?: string) => {
      console.warn('[App] onUnshareError', detail);
      setSharePending(false);
      const reason = (detail ?? '').trim();
      addToast(reason ? `${t('chat.unshareFailed', { defaultValue: '取消分享失败' })}: ${reason}` : t('chat.unshareFailed', { defaultValue: '取消分享失败' }), 'error');
    };
    const onRevertError = (arg: unknown) => {
      let op = 'undo';
      let errorDetail = '';
      try {
        if (typeof arg === 'object' && arg !== null) {
          op = (arg as { op?: string }).op ?? 'undo';
          errorDetail = (arg as { error?: string }).error ?? '';
        } else if (typeof arg === 'string') {
          try {
            const parsed = JSON.parse(arg) as { op?: string; error?: string };
            op = parsed.op ?? 'undo';
            errorDetail = parsed.error ?? '';
          } catch {
            errorDetail = arg;
          }
        }
      } catch {
        // keep default
      }
      const baseMsg = op === 'undo' ? t('chat.revertFailed') : t('chat.restoreFailed');
      addToast(errorDetail ? `${baseMsg}: ${errorDetail}` : baseMsg, 'error');
      // 清除乐观撤销边界，避免重载前占位条还在、而底层消息却已恢复的矛盾状态。
      applyRevertState(false);
      // 回滚乐观状态：重载会话让宿主推送真实的 revert 指针与消息列表
      const sessionId = currentSessionIdRef.current;
      if (sessionId) {
        loadHistorySession(sessionId);
      }
    };
    const onRevertStateUpdate = (...args: unknown[]) => {
      try {
        const update = parseRevertStateEvent(args);
        if (!update) return;
        const { hasRevert: next, messageId: nextId, sessionId: wireSessionId } = update;
        console.debug('[App] onRevertStateUpdate', { wireSessionId, hasRevert: next, messageId: nextId });
        const tabStore = getTabStore();

        // A revert pointer belongs to one session. Applying it to whichever tab
        // is on screen used to reset the scroll flags and jump the reader to the
        // bottom whenever a background session reverted a message.
        if (wireSessionId && tabStore.size() > 0 && !tabStore.isActiveSession(wireSessionId)) {
          tabStore.updateBackgroundTab(wireSessionId, (t) => {
            t.revertBoundaryId = next ? nextId : null;
          });
          return;
        }

        const prevHasRevert = hasRevertStateRef.current;
        const prevId = revertBoundaryIdRef.current;
        const changed = next !== prevHasRevert || nextId !== prevId;
        const currentActiveId = tabStore.getActiveTabId();
        if (currentActiveId) {
          tabStore.updateTab(currentActiveId, (tab) => {
            tab.revertBoundaryId = next ? nextId : null;
          });
        }
        applyRevertState(next, nextId);

        const sessionId = wireSessionId || currentSessionIdRef.current;
        const currentKey = `${sessionId}:${next}:${nextId}`;
        if (changed || (next && lastLoadedRevertStateRef.current !== currentKey)) {
          lastLoadedRevertStateRef.current = currentKey;
          if (sessionId) {
            loadHistorySession(sessionId);
          }
        }
      } catch (err) {
        console.warn('[App] malformed onRevertStateUpdate payload', err, args);
      }
    };
    // 服务端权威删除：opencode 在下一次 prompt 时把 revert 点之后的消息真正删掉，
    // 并逐条广播 message.removed；宿主镜像删除后转发到这里。先把自己列表里的这些
    // 消息剔除，使随后到达的 updateMessages 快照与 prev 等长 —— 否则
    // preserveLatestMessagesOnShrink 会把它当成「暂时收缩」，把刚删的消息抢救回来。
    const onMessagesRemoved = (arg0: unknown, arg1?: unknown) => {
      try {
        // Host sends (sessionId, json); accept the legacy single-argument form
        // for older builds.
        const hasSessionArg = typeof arg1 !== 'undefined';
        const targetSessionId = hasSessionArg && typeof arg0 === 'string' ? arg0 : null;
        const json = hasSessionArg ? arg1 : arg0;
        const parsed = typeof json === 'string' ? JSON.parse(json) as unknown : json;
        if (!Array.isArray(parsed) || parsed.length === 0) return;
        const ids = parsed.filter((id): id is string => typeof id === 'string' && id.length > 0);
        if (ids.length === 0) return;
        console.debug('[App] onMessagesRemoved', targetSessionId, ids);

        if (targetSessionId && getTabStore().size() > 0 && !getTabStore().isActiveSession(targetSessionId)) {
          getTabStore().updateBackgroundTab(targetSessionId, (t) => {
            t.messages = applyMessagesRemoved(t.messages ?? [], ids);
          });
          return;
        }

        setMessages((prev) => applyMessagesRemoved(prev, ids));
      } catch {
        // ignore malformed payloads
      }
    };
    const onCompactSuccess = (targetSessionId?: string, envelope?: HostEventEnvelope) => {
      const ownerTabId = envelope?.tabId
        ?? (targetSessionId ? getTabStore().findIdBySession(targetSessionId) : null)
        ?? getTabStore().getActiveTabId()
        ?? null;
      const isForeground = !targetSessionId
        || getTabStore().size() === 0
        || getTabStore().isActiveSession(targetSessionId);
      // Record the result before the foreground check: a compact that finishes
      // in a background tab must still show its success pill when the user
      // switches back.
      applyCompactNotice(ownerTabId, { status: 'success', at: Date.now() });
      if (!isForeground) {
        return;
      }
      setIsCompacting(false);
      setCompactingStartTime(null);
      setTabCompacting(ownerTabId, false, null);
      // Force scroll to bottom after compact completes
      requestAnimationFrame(() => {
        scrollToBottom();
      });
      const activeSessionId = targetSessionId || currentSessionIdRef.current;
      if (activeSessionId) {
        void loadHistorySession(activeSessionId);
      }
    };
    const onCompactError = (targetSessionId?: string, detail?: string) => {
      const ownerTabId = targetSessionId
        ? getTabStore().findIdBySession(targetSessionId) ?? null
        : getTabStore().getActiveTabId() ?? null;
      const isForeground = !targetSessionId
        || getTabStore().size() === 0
        || getTabStore().isActiveSession(targetSessionId);
      applyCompactNotice(ownerTabId, { status: 'failure', detail, at: Date.now() });
      if (!isForeground) {
        return;
      }
      setIsCompacting(false);
      setCompactingStartTime(null);
      setTabCompacting(ownerTabId, false, null);
      requestAnimationFrame(() => {
        scrollToBottom();
      });
    };
    registerHostEvents({
      'session.fork.done': onForkSuccess,
      'session.fork.bound': onForkBound,
      'session.fork.failed': onForkError,
      'session.share.done': onShareSuccess,
      'session.share.failed': onShareError,
      'session.unshare.done': onUnshareSuccess,
      'session.unshare.failed': onUnshareError,
      'session.revert.failed': onRevertError,
      'revert.changed': onRevertStateUpdate,
      'message.removed': onMessagesRemoved,
      'session.compact.done': onCompactSuccess,
      'session.compact.failed': onCompactError,
    });

    return () => {
      unregisterHostEvents([
        'session.fork.done', 'session.fork.bound', 'session.fork.failed',
        'session.share.done', 'session.share.failed',
        'session.unshare.done', 'session.unshare.failed',
        'session.revert.failed', 'revert.changed', 'message.removed',
        'session.compact.done', 'session.compact.failed',
      ]);
    };
  }, [applyRevertState, loadHistorySession, currentSessionIdRef, addToast, t, setMessages, setIsCompacting, setCompactingStartTime, setTabCompacting, applyCompactNotice, scrollToBottom]);

  // ── Window callbacks (bridge communication) ──
  useWindowCallbacks({
    t, addToast, clearToasts,
    setMessages, setStatus, setLoading, setLoadingStartTime,
    setIsThinking, setStreamingActive, setSessionLoading, setHistoryData,
    setIsCompacting, setCompactingStartTime,
    setCurrentSessionId, setUsagePercentage, setUsageUsedTokens, setUsageMaxTokens,
    setPermissionMode, setCurrentProvider,
    setOpenCodePermissionMode,
    setSelectedOpenCodeModel,
    setReasoningEffort,
    setSendShortcut, setAutoOpenFileEnabled,
    setContextInfo,
    setSubagentHistories,
    setTaskEvents,
    setSseTodos,
    currentProviderRef, messagesContainerRef, isUserAtBottomRef, userPausedRef,
    suppressNextStatusToastRef,
    streamingContentRef, streamingThinkingRef, isStreamingRef, useBackendStreamingRenderRef,
    autoExpandedThinkingKeysRef,
    streamingMessageIndexRef,
    streamingTurnIdRef, turnIdCounterRef,
    lastContentUpdateRef, contentUpdateTimeoutRef,
    lastThinkingUpdateRef, thinkingUpdateTimeoutRef,
    findLastAssistantIndex, extractRawBlocks,
    getOrCreateStreamingAssistantIndex, patchAssistantForStreaming,
    openPermissionDialog, openAskUserQuestionDialog, openPlanApprovalDialog,
    forceClosePermissionDialog, forceCloseAskUserQuestionDialog, invalidateQuestionCard, invalidatePermissionCard, forceClosePlanApprovalDialog,
    openContextUsageDialog, updateContextUsageData,
    closeContextUsageDialog,
    customSessionTitleRef, currentSessionIdRef, updateHistoryTitle, applyHistoryTitleLocal,
    setCustomSessionTitle,
    setPermissionDialogTimeoutSeconds,
  });

  // ── Message processing ──
  // Compact feedback is tab state, not a transcript entry. Injecting it here
  // keeps one success/failure pill across the history reload and tab switches
  // that follow a compaction; handleSubmit clears it with the next user message.
  const messagesWithCompactNotice = useMemo(() => {
    if (!compactNotice) return messages;
    const notice = compactNotice.status === 'failure'
      ? createCompactFailureNotice(t('chat.compactFailed'), compactNotice.detail)
      : createCompactSuccessNotice(t('chat.compactSuccess'));
    return [...messages, notice];
  }, [messages, compactNotice, t]);

  const {
    getMessageText, getContentBlocks,
    mergedMessages, sentAttachmentsRef,
  } = useMessageProcessing({ messages: messagesWithCompactNotice, currentSessionId, t });

  // ── Message sender ──
  const {
    handleSubmit: hookHandleSubmit,
    executeMessage,
    interruptSession,
  } = useMessageSender({
    t, addToast,
    currentProvider, selectedModel, permissionMode, reasoningEffort,
    daemonStatusLoaded, currentSdkInstalled,
    sentAttachmentsRef, chatInputRef, messagesContainerRef,
    isUserAtBottomRef, userPausedRef, isStreamingRef,
    // Streaming buffers + throttle handles — interruptSession needs them to
    // drop buffered content and cancel queued rAFs when the user hits stop.
    streamingContentRef, streamingThinkingRef,
    contentUpdateTimeoutRef, thinkingUpdateTimeoutRef,
    setMessages, setLoading, setLoadingStartTime, setStreamingActive,
    setCurrentView,
    forceCreateNewSession,
    handleModeSelect,
    openContextUsageDialog,
    closeContextUsageDialog,
    currentSessionId,
    currentSessionIdRef,
  });

  // ── "Interrupt then undo/redo" confirmation handlers (busy session) ──
  // Declared here because they depend on `interruptSession` from useMessageSender.
  const handleConfirmInterruptRevert = useCallback(() => {
    const pending = pendingRevert;
    setPendingRevert(null);
    if (!pending) return;
    // Abort first — daemon abort bypasses the command queue, and the queued
    // revert runs as soon as the aborted turn settles.
    interruptSession();
    if (pending.op === 'undo') {
      dispatchRevert(pending.target);
    } else {
      const sid = currentSessionIdRef.current;
      sendSessionCommand('session.unrevert', sid, {});
      applyRevertState(false);
    }
  }, [pendingRevert, interruptSession, dispatchRevert, applyRevertState]);

  const handleCancelInterruptRevert = useCallback(() => {
    setPendingRevert(null);
  }, []);

  // ── /compact 确认门：压缩不可撤销，先弹确认框，用户确认后才真正发送 ──
  const doCompact = useCallback(() => {
    consumeRevertBoundary();
    const compactSessionId = currentSessionIdRef.current;
    sendSessionCommand('session.compact', compactSessionId, { model: selectedModel });
    const startedAt = Date.now();
    setIsCompacting(true);
    setCompactingStartTime(startedAt);
    setTabCompacting(
      compactSessionId ? getTabStore().findIdBySession(compactSessionId) : null,
      true,
      startedAt,
    );
    userPausedRef.current = false;
    isUserAtBottomRef.current = true;
    scrollToBottom();
    requestAnimationFrame(() => {
      scrollToBottom();
    });
  }, [consumeRevertBoundary, selectedModel, setIsCompacting, setCompactingStartTime, setTabCompacting, scrollToBottom, userPausedRef, isUserAtBottomRef, currentSessionIdRef]);
  const { showCompactConfirm, requestCompact, handleCompactConfirmed, handleCancelCompact } =
    useCompactConfirm(doCompact, loading || isCompacting);

  // "Don't ask again" checkbox state for the compact confirm dialog.
  // Resets to unchecked every time the dialog re-opens.
  const [skipCompactAgain, setSkipCompactAgain] = useState(false);
  useEffect(() => {
    if (showCompactConfirm) {
      setSkipCompactAgain(false);
    }
  }, [showCompactConfirm]);

  const handleConfirmCompactWithSkip = useCallback(() => {
    handleCompactConfirmed(skipCompactAgain);
  }, [handleCompactConfirmed, skipCompactAgain]);

  // Handle opencode builtin session commands typed as slash commands
  // (mirror the TUI: /compact /undo /redo /fork /share /unshare).
  const handleBuiltinCommand = useCallback((command: string) => {
    switch (command) {
      case '/compact':
        // 先弹确认框；确认后 doCompact 才发送（不可撤销操作）。
        requestCompact();
        break;
      case '/undo': {
        const id = findLatestUserMessageId();
        if (id) {
          handleUndoMessage({ type: 'user', id } as ClaudeMessage);
        } else {
          // 本地无 id —— 交给宿主通过 listMessages 解析
          handleUndoMessage({ type: 'user' } as ClaudeMessage);
        }
        break;
      }
      case '/redo':
        handleRedoMessage();
        break;
      case '/fork':
        handleForkFull();
        break;
      case '/share':
        void handleShare();
        break;
      case '/unshare':
        void handleUnshare();
        break;
    }
  }, [requestCompact, handleUndoMessage, handleRedoMessage, handleForkFull, handleShare, handleUnshare]);

  // Execute from queue handler (dispatches builtin slash commands or sends message)
  const executeFromQueue = useCallback((content: string, attachments?: Attachment[]) => {
    const text = content.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
    if (text.startsWith('/')) {
      const command = text.split(/\s+/)[0].toLowerCase();
      if (BUILTIN_SESSION_COMMANDS.has(command)) {
        if (command === '/compact' || command === '/fork') {
          consumeRevertBoundary();
        }
        handleBuiltinCommand(command);
        return;
      }
    }
    consumeRevertBoundary();
    executeMessage(content, attachments);
  }, [handleBuiltinCommand, executeMessage, consumeRevertBoundary]);

  // ── Message queue ──
  const {
    queue: messageQueue,
    enqueue: enqueueMessage,
    dequeue: dequeueMessage,
  } = useMessageQueue({ isLoading: loading, isCompacting, onExecute: executeFromQueue });

  // Reset share / compact-confirm / compacting state on session switch
  useEffect(() => {
    // 切换会话时，不再抹杀 revert 状态，因为各 Tab 拥有自己独立的 revertBoundaryId
    resetShareState();
    handleCancelCompact();
    setIsCompacting(false);
    setCompactingStartTime(null);
  }, [resetShareState, currentSessionId, handleCancelCompact, setIsCompacting, setCompactingStartTime]);

  // handleSubmit with queue support (new session and local commands bypass loading check)
  const handleSubmit = useCallback((
    content: string,
    attachments?: Attachment[],
    fileTags?: { displayPath: string; absolutePath: string }[],
  ) => {
    const text = content.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
    const hasAttachments = Array.isArray(attachments) && attachments.length > 0;
    if (!text && !hasAttachments) return;

    // The compact pill reports the context transition the next prompt starts
    // from. Sending that prompt is what acknowledges it.
    setCompactNotice(null);

    // View-switching / session-reset commands bypass busy guard
    if (text.startsWith('/')) {
      const command = text.split(/\s+/)[0].toLowerCase();
      // New session commands
      if (NEW_SESSION_COMMANDS.has(command)) {
        forceCreateNewSession();
        return;
      }
      // /resume - open history view
      if (RESUME_COMMANDS.has(command)) {
        setCurrentView('history');
        return;
      }
    }

    // If loading or compacting, all other messages / commands enter the FIFO queue
    if (loading || isCompacting) {
      enqueueMessage(content, attachments);
      return;
    }

    // Local commands when idle
    if (text.startsWith('/')) {
      const command = text.split(/\s+/)[0].toLowerCase();
      // /plan - switch to plan mode (opencode has a native plan agent)
      if (PLAN_COMMANDS.has(command)) {
        consumeRevertBoundary();
        handleModeSelect('plan');
        addToast(t('chat.planModeEnabled', { defaultValue: 'Plan mode enabled' }), 'info');
        return;
      }
      // /context - handled locally
      if (CONTEXT_COMMANDS.has(command)) {
        consumeRevertBoundary();
        hookHandleSubmit(content, attachments, fileTags);
        return;
      }
      // opencode builtin session commands (compact/undo/redo/fork/share/unshare)
      if (BUILTIN_SESSION_COMMANDS.has(command)) {
        if (command === '/compact' || command === '/fork') {
          consumeRevertBoundary();
        }
        handleBuiltinCommand(command);
        return;
      }
    }

    // 普通消息 / 队列消息 / !shell —— 服务端 prompt 前都会 cleanup revert
    consumeRevertBoundary();
    hookHandleSubmit(content, attachments, fileTags);
  }, [loading, isCompacting, enqueueMessage, hookHandleSubmit, forceCreateNewSession, handleModeSelect, setCurrentView, addToast, t, handleBuiltinCommand, consumeRevertBoundary, setCompactNotice]);

  // ── Chat-view computations (stage 5 of TASK-P1-01) ──
  const {
    findToolResult, getToolResultRaw,
    fileChangeMgmt,
    filteredFileChanges, subagents, globalTodos, sessionTitle,
  } = useChatComputations({
    t, messages, mergedMessages, subagentHistories, customSessionTitle, streamingActive, currentProvider,
    currentSessionId, currentSessionIdRef,
    getMessageText, getContentBlocks, sseTodos,
  });

  const { handleUndoFile, handleDiscardAll: handleDiscardAllRaw, handleKeepAll } = fileChangeMgmt;
  const onDiscardAll = useCallback(
    () => { handleDiscardAllRaw(filteredFileChanges); },
    [handleDiscardAllRaw, filteredFileChanges],
  );

  // Stabilize context value references for SubagentContext consumers.
  const { subagentHistoryCtxValue, sessionIdCtxValue } = useSubagentContextValues(
    subagentHistories,
    currentSessionId,
    currentProvider,
  );

  const handleNavigateToProviderSettings = useCallback(() => {
    setSettingsInitialTab('providers');
    setCurrentView('settings');
  }, [setSettingsInitialTab, setCurrentView]);



  // Session tabs management
  const {
    tabs,
    activeTabId,
    selectTab,
    createTab,
    closeTab,
    closeOtherTabs,
    closeTabsToLeft,
    closeTabsToRight,
    closeAllTabs,
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
  } = useSessionTabs({
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
    sessionLoading,
    setSessionLoading,
    revertBoundaryId,
    setRevertBoundaryId: handleSetRevertBoundaryId,
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
    permissionMode,
    setPermissionMode,
    selectedModel,
    setSelectedModel: handleModelSelectWithEffort,
    reasoningEffort,
    setReasoningEffort: handleReasoningChange,
    contextInfo,
    setContextInfo,
    loadingStartTime,
    setLoadingStartTime,
    usagePercentage,
    setUsagePercentage,
    usageUsedTokens,
    setUsageUsedTokens,
    usageMaxTokens,
    setUsageMaxTokens,
    availableModels,
    currentProvider,
    isShared,
    setCurrentView,
    chatInputRef,
    defaultAutoOpenFileEnabled: autoOpenFileEnabled,
    forceCreateNewSession,
    loadHistorySession,
    deleteTabRuntime: deleteRuntimeForTab,
    setTabRuntimeStreaming: setRuntimeStreamingForTab,
    captureScrollState,
    restoreScrollState,
    t,
  });

  useEffect(() => {
    openSessionInTabRef.current = openSessionInTab;
    appTabsRef.current = tabs;
  }, [openSessionInTab, tabs]);

  // Reset message anchor rail collapsed count when active tab or session switches
  useEffect(() => {
    setAnchorCollapsedCount(0);
  }, [activeTabId, currentSessionId]);

  // ── Render ──
  return (
    <>
      <ToastContainer messages={toasts} onDismiss={dismissToast} />
      <ChatHeader
        currentView={currentView}
        t={t}
        tabs={tabs}
        activeTabId={activeTabId}
        onSelectTab={selectTab}
        onCloseTab={closeTab}
        onCloseOtherTabs={closeOtherTabs}
        onCloseTabsToLeft={closeTabsToLeft}
        onCloseTabsToRight={closeTabsToRight}
        onCloseAllTabs={closeAllTabs}
        onRenameTab={renameTab}
        onForkTab={forkTab}
        onReorderTabs={reorderTabs}
        onShareTab={(tab) => {
          if (tab.id !== activeTabId) selectTab(tab.id);
          void handleShare(tab.sessionId ?? null);
        }}
        onCopyShareLinkTab={(tab) => {
          if (tab.id !== activeTabId) selectTab(tab.id);
          handleCopyShareLink();
        }}
        onUnshareTab={(tab) => {
          if (tab.id !== activeTabId) selectTab(tab.id);
          void handleUnshare(tab.sessionId);
        }}
        onBack={() => setCurrentView('chat')}
        onNewSession={createTab}
        onHistory={() => setCurrentView('history')}
        onSettings={() => {
          setSettingsInitialTab(undefined);
          setCurrentView('settings');
        }}
        onOpenSearch={() => setSearchOpen(true)}
        onExportTab={(tab) => {
          const tabMessages = tab.id === activeTabId ? messages : (tab.messages || []);
          const title = tab.title || t('common.newSession', { defaultValue: '新会话' });
          const md = convertMessagesToMarkdown(tabMessages, title);
          downloadMarkdown(md, `${title}.md`);
          addToast(t('history.exportSuccess', { defaultValue: '会话已导出' }), 'success');
        }}
      />

      {currentView === 'settings' ? (
        <SettingsView
          onClose={() => setCurrentView('chat')}
          initialTab={settingsInitialTab}
          currentProvider={currentProvider}
          sendShortcut={sendShortcut}
          onSendShortcutChange={handleSendShortcutChange}
          autoOpenFileEnabled={autoOpenFileEnabled}
          onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
          permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
          onPermissionDialogTimeoutChange={setPermissionDialogTimeoutSeconds}
        />
      ) : (
        <>
          {/* Keep ChatScreen mounted while browsing history so model catalog,
              scroll position, and draft attachments survive history ↔ chat. */}
          <div
            style={currentView === 'chat'
              ? { display: 'flex', flex: 1, minHeight: 0, flexDirection: 'column', overflow: 'hidden' }
              : { display: 'none' }}
          >
            <ChatScreen
              mergedMessages={mergedMessages}
              sessionTitle={sessionTitle}
              getMessageText={getMessageText}
              getContentBlocks={getContentBlocks}
              findToolResult={findToolResult}
              getToolResultRaw={getToolResultRaw}
              subagents={subagents}
              globalTodos={globalTodos}
              filteredFileChanges={filteredFileChanges}
              subagentHistoryCtxValue={subagentHistoryCtxValue}
              sessionIdCtxValue={sessionIdCtxValue}
              chatInputRef={chatInputRef}
              messagesContainerRef={messagesContainerRef}
              messagesEndRef={messagesEndRef}
              inputAreaRef={inputAreaRef}
              messageNodeMapRef={messageNodeMapRef}
              messageListRef={messageListRef}
              isAutoScrollingRef={isAutoScrollingRef}
              anchorCollapsedCount={anchorCollapsedCount}
              setAnchorCollapsedCount={setAnchorCollapsedCount}
              onMessageNodeRef={handleMessageNodeRef}
              statusPanelExpanded={statusPanelExpanded}
              statusPanelTab={statusPanelTab}
              onStatusPanelTabChange={setStatusPanelTab}
              onToggleStatusPanel={toggleStatusPanel}
              onUndoFile={handleUndoFile}
              onDiscardAll={onDiscardAll}
              onKeepAll={handleKeepAll}
              onSubmit={handleSubmit}
              onInterrupt={interruptSession}
              onNavigateToProviderSettings={handleNavigateToProviderSettings}
              revertBoundaryId={revertBoundaryId}
              onUndo={handleUndoMessage}
              onRestore={handleRedoMessage}
              onFork={handleForkRequest}
              currentProvider={currentProvider}
              selectedModel={selectedModel}
              permissionMode={permissionMode}
              currentSdkInstalled={currentSdkInstalled}
              daemonStatusLoaded={daemonStatusLoaded}
              retryDaemonStatus={retryDaemonStatus}
              activeProviderConfig={activeProviderConfig}
              reasoningEffort={reasoningEffort}
              sendShortcut={sendShortcut}
              autoOpenFileEnabled={activeTabAutoOpenFileEnabled}
              usagePercentage={usagePercentage}
              usageUsedTokens={usageUsedTokens}
              usageMaxTokens={usageMaxTokens}
              onModeSelect={handleModeSelect}
              onModelSelect={handleModelSelectWithEffort}
              onReasoningChange={handleReasoningChange}
              onToggleThinking={handleToggleThinking}
              onAutoOpenFileEnabledChange={setActiveTabAutoOpenFileEnabled}
              messageQueue={messageQueue}
              onRemoveFromQueue={dequeueMessage}
            />
          </div>
          {currentView === 'history' && (
            <HistoryView
              historyData={historyData}
              currentProvider={currentProvider}
              onLoadSession={(sessionId) => {
                const session = historyData?.sessions?.find(s => s.sessionId === sessionId);
                openSessionInTab(
                  sessionId,
                  session?.title,
                  session?.model,
                  session?.agent || (session as any)?.mode,
                  (session as any)?.reasoningEffort,
                );
              }}
              onDeleteSession={deleteHistorySession}
              onDeleteSessions={deleteHistorySessions}
              onExportSession={exportHistorySession}
              onToggleFavorite={toggleFavoriteSession}
              onUpdateTitle={updateHistoryTitle}
              onRetry={handleRetryHistory}
            />
          )}
        </>
      )}

      <div id="image-preview-root" />

      <AppDialogs
        showInterruptConfirm={showInterruptConfirm}
        onConfirmInterrupt={handleConfirmInterrupt}
        onCancelInterrupt={handleCancelInterrupt}
        permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
        promptsVisible={currentView === 'chat'}
      />

      <ConfirmDialog
        isOpen={pendingRevert !== null}
        title={pendingRevert?.op === 'undo' ? t('chat.undoTooltip') : t('chat.redoTooltip')}
        message={pendingRevert?.op === 'undo'
          ? t('chat.revertBusyConfirmMessage')
          : t('chat.restoreBusyConfirmMessage')}
        confirmText={t('common.confirm')}
        cancelText={t('common.cancel')}
        onConfirm={handleConfirmInterruptRevert}
        onCancel={handleCancelInterruptRevert}
      />

      <ConfirmDialog
        isOpen={showCompactConfirm}
        title={t('chat.compactConfirmTitle')}
        message={t('chat.compactConfirmMessage')}
        confirmText={t('chat.compactConfirmAction')}
        cancelText={t('common.cancel')}
        onConfirm={handleConfirmCompactWithSkip}
        onCancel={handleCancelCompact}
      >
        <label className="confirm-dialog-dont-ask-again">
          <input
            type="checkbox"
            checked={skipCompactAgain}
            onChange={(e) => setSkipCompactAgain(e.target.checked)}
          />
          <span>{t('common.dontAskAgain')}</span>
        </label>
      </ConfirmDialog>
    </>
  );
};

export default App;
