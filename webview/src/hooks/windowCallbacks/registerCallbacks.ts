/**
 * registerCallbacks.ts
 *
 * Single entry point that mounts all window bridge callbacks.  Called once
 * inside useWindowCallbacks' useEffect.  Receives the full options bag from
 * the hook rather than individual parameters to keep the call-site tidy.
 *
 * Pure functions have been extracted to messageSync.ts / resetTransientState.ts /
 * settingsBootstrap.ts; callback groups are further split into dedicated
 * sub-modules under registerCallbacks/ for easier navigation and maintenance.
 */

import type { UseWindowCallbacksOptions } from '../useWindowCallbacks';
import { cardDebugLog } from '../../utils/bridge';
import { parseSubagentStatusEvent, parseTaskNotification } from '../../utils/taskEventParser';
import { deepEqual } from '../../utils/deepEqual';
import type { SubagentHistoryResponse, TaskEvent } from '../../types/subagent';
import { todoListFromPayload } from '../../utils/todoShared';
import {
  setupSlashCommandsCallback,
  resetSlashCommandsState,
  resetFileReferenceState,
  setupDollarCommandsCallback,
  resetDollarCommandsState,
} from '../../components/ChatInputBox/providers';
import { buildResetTransientUiState } from './resetTransientState';
import { startModeRequest } from './settingsBootstrap';
import { registerMessageCallbacks } from './registerCallbacks/messageCallbacks';
import { registerStreamingCallbacks } from './registerCallbacks/streamingCallbacks';
import { registerSessionCallbacks } from './registerCallbacks/sessionCallbacks';
import { registerUsageModeCallbacks } from './registerCallbacks/usageModeCallbacks';
import { registerPermissionCallbacks } from './registerCallbacks/permissionCallbacks';
import { registerAgentAndSelectionCallbacks } from './registerCallbacks/agentCallbacks';
import { registerQueueCallbacks } from './registerCallbacks/queueCallbacks';
import { readJson, type HostEventEnvelope, type HostEventPayload } from '../../utils/hostEventTypes';
import { hostEventHandler, registerHostEvents } from '../../utils/hostEventRegistry';
import { getTabStore } from '../../store/tabStore';
import { owningTabIdForSession } from './tabTransition';

function areSubagentMessagesEquivalent(previousMessages: unknown[] | undefined, nextMessages: unknown[] | undefined): boolean {
  if (previousMessages === nextMessages) return true;
  return deepEqual(previousMessages, nextMessages);
}

const pendingSubagentHistoryChunks = new Map<string, string[]>();
const MAX_PENDING_SUBAGENT_HISTORY_TRANSFERS = 16;
const MAX_SUBAGENT_HISTORIES = 50;
/** A terminal child transcript is final for its tool call. */
const TERMINAL_SUBAGENT_STATUSES = new Set(['completed', 'error']);

/**
 * Merge one subagent transcript into a tab-owned map.
 *
 * The map is capped by insertion order so a long-lived tab cannot accumulate an
 * unbounded number of full transcripts. Returning the previous object when the
 * payload is identical keeps polling from re-rendering the whole status panel.
 */
function mergeSubagentHistory(
  prev: Record<string, SubagentHistoryResponse>,
  key: string,
  result: SubagentHistoryResponse,
): Record<string, SubagentHistoryResponse> {
  const existing = prev[key];
  // Two requests can be in flight together (the expand-time read and the
  // post-terminal refresh). The expand-time snapshot is taken while the child
  // may still be running, so it can land after the terminal one; a `running`
  // answer must not demote a terminal record. Terminal is final per tool call.
  if (existing?.status && TERMINAL_SUBAGENT_STATUSES.has(existing.status) && result.status === 'running') {
    return prev;
  }
  if (existing && existing.success === result.success
    && existing.completed === result.completed
    && existing.status === result.status
    && existing.error === result.error
    && existing.sessionId === result.sessionId
    && existing.provider === result.provider
    && existing.toolUseId === result.toolUseId
    && existing.agentId === result.agentId
    && existing.agentPath === result.agentPath
    && areSubagentMessagesEquivalent(existing.messages, result.messages)) {
    return prev;
  }
  const next: Record<string, SubagentHistoryResponse> = { ...prev, [key]: result };
  if (Object.keys(next).length <= MAX_SUBAGENT_HISTORIES) {
    return next;
  }
  const oldestFirst = Object.keys(next).filter((k) => k !== key);
  const keep = new Set([...oldestFirst.slice(oldestFirst.length - (MAX_SUBAGENT_HISTORIES - 1)), key]);
  for (const k of Object.keys(next)) {
    if (!keep.has(k)) delete next[k];
  }
  return next;
}

function appendSubagentHistoryChunk(transferId: string, chunk: string, isFinal: string | boolean): void {
  if (!transferId) return;
  const chunks = pendingSubagentHistoryChunks.get(transferId) ?? [];
  chunks.push(chunk);
  if (isFinal === true || isFinal === 'true') {
    pendingSubagentHistoryChunks.delete(transferId);
    hostEventHandler('subagent.history')?.(chunks.join(''));
    return;
  }
  if (pendingSubagentHistoryChunks.size >= MAX_PENDING_SUBAGENT_HISTORY_TRANSFERS) {
    const oldestTransferId = pendingSubagentHistoryChunks.keys().next().value;
    if (oldestTransferId) pendingSubagentHistoryChunks.delete(oldestTransferId);
  }
  pendingSubagentHistoryChunks.set(transferId, chunks);
}

export function registerWindowCallbacks(
  options: UseWindowCallbacksOptions,
  requestHistoryRenderCommit: (refreshEpoch: number) => void,
): void {
  // -------------------------------------------------------------------------
  // Session transition helpers
  // -------------------------------------------------------------------------

  const resetTransientUiState = buildResetTransientUiState({
    clearToasts: options.clearToasts,
    setStatus: options.setStatus,
    setLoading: options.setLoading,
    setLoadingStartTime: options.setLoadingStartTime,
    setIsThinking: options.setIsThinking,
    setStreamingActive: options.setStreamingActive,
    isStreamingRef: options.isStreamingRef,
    useBackendStreamingRenderRef: options.useBackendStreamingRenderRef,
    streamingMessageIndexRef: options.streamingMessageIndexRef,
    streamingContentRef: options.streamingContentRef,
    streamingThinkingRef: options.streamingThinkingRef,
    autoExpandedThinkingKeysRef: options.autoExpandedThinkingKeysRef,
    contentUpdateTimeoutRef: options.contentUpdateTimeoutRef,
    thinkingUpdateTimeoutRef: options.thinkingUpdateTimeoutRef,
    streamingTurnIdRef: options.streamingTurnIdRef,
    // Nothing has to be archived any more: the streaming runtime belongs to
    // the tab, and the refs below are the active tab's view onto it. Wiping
    // them therefore only clears the tab the user is looking at.
  });

  // Expose as single entry point for session transition cleanup.
  // beginSessionTransition (useSessionManagement) calls this to synchronously
  // clear both React state AND internal refs in one shot.
  window.__resetTransientUiState = resetTransientUiState;

  /**
   * Ask Java for one more OSR surface publication of the current DOM.
   *
   * Switching tabs replaces the whole transcript inside a single surface — the
   * same situation as a history render, but it produces no
   * `history_dom_committed`. Without this the OSR backing image keeps showing
   * the outgoing session until some later paint happens to arrive.
   */
  window.__requestSurfaceRepublish = () => {
    const refreshEpoch = (window.__historySurfaceRefreshEpoch ?? 0) + 1;
    window.__historySurfaceRefreshEpoch = refreshEpoch;
    requestHistoryRenderCommit(refreshEpoch);
  };

  // =========================================================================
  // Register callback groups
  // =========================================================================

  registerMessageCallbacks(options, resetTransientUiState, requestHistoryRenderCommit);
  registerStreamingCallbacks(options);
  registerSessionCallbacks(options);
  registerUsageModeCallbacks(options);
  registerPermissionCallbacks(options);
  registerAgentAndSelectionCallbacks(options);
  registerQueueCallbacks(options);

  const onSubagentHistoryLoaded = (payload: string | { history?: SubagentHistoryResponse }) => {
    // 2 秒轮询结果如果直接写进 active state，会把 A tab 的子代理 transcript
    // 显示到 B tab。先按 payload.sessionId 解析目标 tab；解析不到就丢弃，
    // 绝不回落到 active tab。
    try {
      if (!options.setSubagentHistories) return;
      const raw = typeof payload === 'string' ? payload : payload?.history;
      if (!raw) return;
      const result = (typeof raw === 'string' ? JSON.parse(raw) : raw) as SubagentHistoryResponse;
      const key = result.toolUseId || result.agentId;
      if (!key) return;

      const tabStore = getTabStore();
      const targetTabId = result.sessionId
        ? tabStore.findIdBySession(result.sessionId)
        : null;
      if (result.sessionId && tabStore.size() > 0) {
        if (!targetTabId) return;
        const isActive = tabStore.isActiveTab(targetTabId);
        if (!isActive) {
          const updated = tabStore.updateTab(targetTabId, (tab) => ({
            ...tab,
            subagentHistories: mergeSubagentHistory(
              tab.subagentHistories || {},
              key,
              result,
            ),
          }));
          if (updated) return;
          // A known session whose tab is gone must not leak into the active tab.
          return;
        }
      }

      options.setSubagentHistories((prev) => mergeSubagentHistory(prev, key, result));
    } catch {
      // Ignore malformed callback payloads; the request can be retried by reopening the Agent row.
    }
  };

  // task_* SDK system events signal the lifecycle of a background Agent
  // (Agent/Task tool invoked with run_in_background:true). Only
  // task_notification carries a terminal status; task_started / task_progress
  // merely announce progress and must not flip the running state. Without
  // this, the StatusPanel cannot tell a launched async agent from a finished
  // one, and the completion summary never surfaces.
  //
  // Cross-session safety is enforced by three layers, so this handler does
  // not re-check sessionId (which is not part of the taskEvent payload):
  //   1. Java OpencodeBuddyChatWindow.titleEventListener drops events whose sessionId
  //      does not match the active session.
  //   2. beginSessionTransition (useSessionManagement) clears taskEvents on
  //      session switch, so stale entries from the prior session cannot linger.
  //   3. tool_use_ids are globally unique, so even a delayed event can only
  //      update the agent it was emitted for, never mislabel another.
  /**
   * Store the latest terminal run event for the tab that owns it.
   *
   * <p>Two transports end here: the legacy Claude-shaped `task.event` and
   * opencode's engine-neutral `subagent.status` (inline and background). Routing
   * is by the envelope session, so a background tab receives its own state and
   * the active tab never absorbs a sibling session's agent.</p>
   */
  const applyTaskEvent = (taskEvent: TaskEvent, targetSessionId: string | null) => {
    const tabStore = getTabStore();
    const ownerTabId = owningTabIdForSession(targetSessionId);
    // Only an identity no tab claims is dropped; an event without any identity
    // keeps the window-level behaviour.
    if (targetSessionId && tabStore.size() > 0 && !ownerTabId) return;
    if (ownerTabId && !tabStore.isActiveTab(ownerTabId)) {
      tabStore.updateTab(ownerTabId, (t) => ({
        ...t,
        taskEvents: { ...(t.taskEvents || {}), [taskEvent.toolUseId]: taskEvent },
      }));
      return;
    }

    if (!options.setTaskEvents) return;
    const { toolUseId } = taskEvent;
    options.setTaskEvents((prev) => {
      const existing = prev[toolUseId];
      if (existing
        && existing.status === taskEvent.status
        && existing.summary === taskEvent.summary
        && existing.totalTokens === taskEvent.totalTokens
        && existing.totalToolUseCount === taskEvent.totalToolUseCount
        && existing.totalDurationMs === taskEvent.totalDurationMs
        && existing.agentId === taskEvent.agentId
        && existing.outputFilePath === taskEvent.outputFilePath) {
        return prev;
      }
      return { ...prev, [toolUseId]: taskEvent };
    });
  };

  const onTaskEvent = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    try {
      const taskEvent = parseTaskNotification(readJson<any>(payload?.event));
      if (!taskEvent) return;
      applyTaskEvent(taskEvent, targetSessionId);
    } catch {
      // Ignore malformed task event payloads.
    }
  };

  /**
   * Canonical terminal state for a subagent run, pushed by core.
   *
   * <p>opencode v1 (`task`) and v2 (`subagent`) both arrive here after core has
   * resolved the child session, and core publishes it for inline calls too (the
   * parent tool result is the child's final report). This is therefore the one
   * live status source for every subagent run: no engine branches, no polling,
   * and no "is it async?" special case at the call site.</p>
   */
  const onSubagentStatus = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    try {
      const raw = readJson<any>(payload?.subagent);
      const record = typeof raw === 'string' ? JSON.parse(raw) : raw;
      const taskEvent = parseSubagentStatusEvent(record);
      if (!taskEvent) return;
      applyTaskEvent(taskEvent, targetSessionId);
    } catch {
      // Ignore malformed status payloads; the parent tool result remains the
      // replay/bootstrap source for the card.
    }
  };

  /**
   * The engine's canonical task list, routed by tab id.
   *
   * <p>The host owns the session↔tab binding and stamps the owning tab on the
   * event, so this handler never reverse-looks-up a session: the event's tab
   * decides where the list lands. A tab this view has not learned is dropped
   * rather than written onto the conversation on screen, and only a legacy
   * event that names no tab may target the active view.</p>
   */
  const onTodoUpdated = (eventPayload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const raw = eventPayload?.todos;
    if (raw == null) return;
    try {
      // core publishes `{ sessionID, revision, todos }`; older publishers sent
      // the bare array. Both shapes are accepted here.
      const todos = todoListFromPayload(raw);
      if (todos === null) {
        cardDebugLog('[onTodoUpdated] not a task list, dropped');
        return;
      }

      const tabStore = getTabStore();
      const tabId: string | null = env?.tabId ?? null;
      if (!tabId) {
        cardDebugLog('[onTodoUpdated] legacy update, todos:', todos.length);
        if (options.setSseTodos) {
          options.setSseTodos(todos);
        } else {
          tabStore.updateActiveTab((tab) => ({ ...tab, sseTodos: todos }));
        }
        return;
      }
      if (!tabStore.findById(tabId)) {
        cardDebugLog('[onTodoUpdated] unknown tab, dropped:', tabId);
        return;
      }
      tabStore.updateTab(tabId, (tab) => ({ ...tab, sseTodos: todos }));
    } catch (e) {
      console.error('[onTodoUpdated] parse error:', e);
    }
  };

  registerHostEvents({
    'internal.subagent.historyChunk': appendSubagentHistoryChunk,
    'subagent.history': onSubagentHistoryLoaded,
    'subagent.status': onSubagentStatus,
    'task.event': onTaskEvent,
    'todos.updated': onTodoUpdated,
  });

  // =========================================================================
  // Slash Commands Setup
  // =========================================================================

  resetSlashCommandsState();
  resetDollarCommandsState();
  resetFileReferenceState();
  setupSlashCommandsCallback();
  setupDollarCommandsCallback();

  // =========================================================================
  // Request Initial States
  // =========================================================================

  startModeRequest();

  // Host events may have arrived while React was mounting. Replay them now
  // that every callback group has been registered.
  window.__flushOpencodeDispatch?.();
}
