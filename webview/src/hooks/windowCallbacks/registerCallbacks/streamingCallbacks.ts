/**
 * streamingCallbacks.ts
 *
 * Registers window bridge callbacks for streaming:
 * onStreamStart, onContentDelta, onThinkingDelta, onStreamEnd, onPermissionDenied.
 */

import { startTransition } from 'react';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { ClaudeMessage, ClaudeRawMessage } from '../../../types';
import { sendWindowCommand, cardDebugLog } from '../../../utils/bridge';
import { registerHostEvents } from '../../../utils/hostEventRegistry';
import { isSessionTransitioning, owningTabIdForSession } from '../tabTransition';
import { readJson, type HostEventEnvelope, type HostEventPayload } from '../../../utils/hostEventTypes';
import { THROTTLE_INTERVAL } from '../../useStreamingMessages';
import { parseSequence, advanceMinAcceptedSequence } from '../sequenceBarrier';
import { getStreamEndHandlingMode } from '../messageSync';
import { reconstructTurnMetadata } from '../../../utils/turnMetadataReconstruction';
import { resolveSessionId } from '../../../utils/sessionIdentity';
import { getTabStore } from '../../../store/tabStore';

/**
 * Per-session turn start timestamp registry to prevent cross-session timer pollution
 * when users switch tabs during streaming.
 */
const sessionTurnStartTimes = new Map<string, number>();

/**
 * Pour every tool_use_id carried by tool_result blocks inside one message's raw
 * into the supplied accumulator.
 *
 * Writing into a caller-provided Set (instead of returning a fresh one) lets
 * {@link collectUnresolvedToolUseIds} fold every message into a single pass
 * without per-message intermediate allocations. A no-op when the message has
 * no usable raw or no tool_result blocks.
 */
const harvestToolResultIdsInto = (
  message: ClaudeMessage | undefined,
  target: Set<string>,
): void => {
  if (!message?.raw) return;
  const rawObj = typeof message.raw === 'string' ? JSON.parse(message.raw) : message.raw;
  const content = rawObj?.content ?? rawObj?.message?.content;
  if (!Array.isArray(content)) return;
  for (const block of content as Array<{ type?: string; tool_use_id?: string }>) {
    if (block?.type === 'tool_result' && block.tool_use_id) {
      target.add(block.tool_use_id);
    }
  }
};

/**
 * Scans assistant messages containing tool_use blocks and returns IDs that have
 * no matching tool_result anywhere in the conversation.
 *
 * scope: 'lastTurn'  — only inspect the most recent assistant tool_use group and
 *                       every consecutive user message trailing it (default;
 *                       used by onPermissionDenied + onStreamEnd, where only the
 *                       active turn can have stragglers). The trailing sweep
 *                       stops at the first non-user message so results from a
 *                       later turn never leak into this turn's resolution set.
 * scope: 'all'       — collect every tool_use ID across the whole message list
 *                       and check against every tool_result block anywhere.
 *                       Required by historyLoadComplete because a replayed
 *                       Codex session may contain multiple aborted turns whose
 *                       missing results would otherwise be invisible to the
 *                       lastTurn heuristic.
 *
 * Without this, tool blocks like BashToolGroupBlock keep rendering pending
 * spinners forever because parseBashItem treats `result == null` as "still
 * running".
 */
export function collectUnresolvedToolUseIds(
  messages: ClaudeMessage[],
  scope: 'lastTurn' | 'all' = 'lastTurn',
): string[] {
  const idsToAdd: string[] = [];
  try {
    if (scope === 'all') {
      // Pass 1: gather every tool_result id present anywhere in the conversation.
      const resolvedIds = new Set<string>();
      for (const msg of messages) {
        harvestToolResultIdsInto(msg, resolvedIds);
      }
      // Pass 2: flag every assistant tool_use without a matching result.
      for (const msg of messages) {
        if (msg.type !== 'assistant' || !msg.raw) continue;
        const rawObj = typeof msg.raw === 'string' ? JSON.parse(msg.raw) : msg.raw;
        const content = rawObj?.content ?? rawObj?.message?.content;
        if (!Array.isArray(content)) continue;
        for (const block of content as Array<{ type?: string; id?: string }>) {
          if (block?.type === 'tool_use' && block.id
              && !resolvedIds.has(block.id)
              && !window.__deniedToolIds?.has(block.id)) {
            idsToAdd.push(block.id);
          }
        }
      }
      return idsToAdd;
    }

    // scope === 'lastTurn'
    for (let i = messages.length - 1; i >= 0; i--) {
      const msg = messages[i];
      if (msg.type !== 'assistant' || !msg.raw) continue;
      const rawObj = typeof msg.raw === 'string' ? JSON.parse(msg.raw) : msg.raw;
      const content = rawObj.content || rawObj.message?.content;
      if (!Array.isArray(content)) continue;

      const toolUses = content.filter(
        (block: { type?: string; id?: string }) =>
          block.type === 'tool_use' && block.id,
      ) as Array<{ type: string; id: string; name?: string }>;
      if (toolUses.length === 0) continue;

      // Collect tool_result ids from EVERY user message trailing this assistant,
      // not just messages[i + 1]. The backend (ClaudeMessageHandler.handleToolResult)
      // emits one user message per tool_result, so N parallel tool_use fan out into
      // N consecutive user messages. Stopping at the first result message would
      // strand the remaining tool_use ids and mark them as interrupted (red badge)
      // even though their results are present further down the list. The sweep
      // halts at the first non-user message (e.g. the next assistant) so results
      // belonging to a later turn never leak into this turn's resolution set.
      const existingResultIds = new Set<string>();
      for (let j = i + 1; j < messages.length; j++) {
        const follower = messages[j];
        if (follower?.type !== 'user') break;
        harvestToolResultIdsInto(follower, existingResultIds);
      }

      for (const tu of toolUses) {
        if (!existingResultIds.has(tu.id) && !window.__deniedToolIds?.has(tu.id)) {
          let resolvedElsewhere = false;
          for (const m of messages) {
            harvestToolResultIdsInto(m, existingResultIds);
            if (existingResultIds.has(tu.id)) {
              resolvedElsewhere = true;
              break;
            }
          }
          if (!resolvedElsewhere) {
            idsToAdd.push(tu.id);
          }
        }
      }
      break;
    }
  } catch (e) {
    console.error('[Frontend] Error in collectUnresolvedToolUseIds:', e);
  }
  return idsToAdd;
}

export function registerStreamingCallbacks(options: UseWindowCallbacksOptions): void {
  /**
   * The tab an event belongs to: the named tab, else the session's binding
   * (including a tab that is still switching to that session).
   */
  const ownerTabFor = (
    targetTabId: string | null | undefined,
    targetSessionId: string | null | undefined,
  ): string | null => targetTabId ?? owningTabIdForSession(targetSessionId);

  const {
    setStreamingActive,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    setExpandedThinking,
    streamingContentRef,
    streamingThinkingRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    autoExpandedThinkingKeysRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    turnIdCounterRef,
    lastContentUpdateRef,
    contentUpdateTimeoutRef,
    lastThinkingUpdateRef,
    thinkingUpdateTimeoutRef,
    patchAssistantForStreaming,
  } = options;

  // ── Stream stall watchdog ──
  // Tracks the last time we received any streaming activity (delta or
  // updateMessages during streaming).  A periodic check auto-recovers
  // if the backend's onStreamEnd signal was silently lost.
  // Exposed on window so messageCallbacks can also bump this on updateMessages.
  //
  // The interval handle is stored on `window` so that if registerStreamingCallbacks
  // is called again (e.g., HMR, parent re-render), the previous interval is
  // cleared first — preventing multiple watchdogs from running simultaneously.
  if (window.__stallWatchdogInterval != null) {
    clearInterval(window.__stallWatchdogInterval);
    window.__stallWatchdogInterval = null;
  }
  window.__lastStreamActivityAt = 0;

  const clearStallWatchdog = () => {
    if (window.__stallWatchdogInterval != null) {
      clearInterval(window.__stallWatchdogInterval);
      window.__stallWatchdogInterval = null;
    }
  };

  const startStallWatchdog = () => {
    // Intentionally no-op: do not auto-interrupt streaming turns on inactivity,
    // as long-running commands, large codebase indexing, or deep reasoning may take arbitrarily long.
    clearStallWatchdog();
    window.__lastStreamActivityAt = Date.now();
  };

  const onStreamStart = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    // The identity travels in the envelope, the content in the payload.
    const targetSessionId: string | null = env?.sessionId ?? null;
    const targetTabId: string | null = env?.tabId ?? null;
    const mode = payload?.mode as string | boolean | undefined;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    const ownerTabId = ownerTabFor(targetTabId, targetSessionId);
    // No tab owns this identity: a closed tab has nowhere to write, and falling
    // through would start the stream of whichever tab is on screen.
    if (hasTabs && !ownerTabId) return;
    const isBackground = Boolean(ownerTabId) && !tabStore.isActiveTab(ownerTabId);
    if (isBackground) {
      const now = Date.now();
      if (targetSessionId) {
        sessionTurnStartTimes.set(targetSessionId, now);
      }
      const markStarted = (t: any) => {
        t.streamingActive = true;
        t.loading = true;
        t.turnStartedAt = now;
      };
      tabStore.updateTab(ownerTabId, markStarted);
      return;
    }

    // A tab that is switching sessions ignores the events of the session it is
    // leaving. Other tabs are unaffected: the guard is per tab.
    if (isSessionTransitioning(targetSessionId)) return;
    const isReplayStart = mode === 'replay' || mode === true;
    // Clear any stale pending updateMessages from previous turn.
    // This prevents onStreamEnd from using outdated snapshot data.
    if (typeof window.__cancelPendingUpdateMessages === 'function') {
      window.__cancelPendingUpdateMessages();
    }
    // Explicit null in case the rAF already executed (clearing pendingUpdateRaf)
    // but __pendingUpdateJson was not yet cleared by the rAF callback.
    window.__pendingUpdateJson = null;
    // Clear the previous stream-ended marker when a new turn starts
    window.__lastStreamEndedTurnId = undefined;
    window.__lastStreamEndedAt = undefined;
    // Clear idempotency guard for the new turn
    window.__streamEndProcessedTurnId = undefined;
    // Record turn start time for duration calculation in onStreamEnd
    const turnNow = Date.now();
    window.__turnStartedAt = turnNow;
    // Record the start against the event's own session: during a switch the
    // active tab still carries the outgoing one, which lost the duration.
    const turnSessionId = targetSessionId
      ?? tabStore.getActiveSessionId()
      ?? options.currentSessionIdRef?.current; // legacy-allow: a window with no tab strip has only the view's session
    if (turnSessionId) {
      sessionTurnStartTimes.set(turnSessionId, turnNow);
    }
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    isStreamingRef.current = true;
    startStallWatchdog();
    useBackendStreamingRenderRef.current = false;
    autoExpandedThinkingKeysRef.current.clear();
    setStreamingActive(true);

    // FIX: Always reset streamingMessageIndexRef regardless of backend streaming mode
    streamingMessageIndexRef.current = -1;
    turnIdCounterRef.current += 1;
    streamingTurnIdRef.current = turnIdCounterRef.current;
    getTabStore().mutateActiveTranscript((transcript) => {
      const index = transcript.beginAssistant(streamingTurnIdRef.current, isReplayStart);
      if (index >= 0) streamingMessageIndexRef.current = index;
    });
  };

  // rAF-scheduled streaming update: frame-aligned, avoids setTimeout jank.
  // Factory that creates a throttled scheduler bound to a specific timeoutRef +
  // lastUpdateRef pair.  patchAssistantForStreaming reads streamingContentRef /
  // streamingThinkingRef from the hook closure, so the factory only needs to
  // know which ref pair to guard against double-scheduling.
  const createStreamingRafScheduler = (
    timeoutRef: React.MutableRefObject<number | null>,
    lastUpdateRef: React.MutableRefObject<number>,
  ) => {
    const scheduleRaf = (): void => {
      if (timeoutRef.current != null) return;
      const tabStore = getTabStore();
      const scheduledTabId = tabStore.getActiveTabId() || getTabStore().getActiveTabId() || null;
      timeoutRef.current = requestAnimationFrame(() => {
        timeoutRef.current = null;
        // A tab switch between scheduling and this frame must not paint the
        // outgoing tab's buffer into the new active tab.
        const currentTabId = tabStore.getActiveTabId() || getTabStore().getActiveTabId() || null;
        if (scheduledTabId && currentTabId && scheduledTabId !== currentTabId) {
          return;
        }
        // Guard: the turn may have ended (or been interrupted by the user)
        // while this frame was queued. patchAssistantForStreaming reads the
        // delta buffers unconditionally and force-sets isStreaming: true, so a
        // stale frame would flush buffered content into whichever assistant
        // message streamingMessageIndexRef still points at — leaking one turn's
        // text into another's bubble. Bail out; the authoritative onStreamEnd
        // updater owns the final write for that message.
        if (!isStreamingRef.current) return;
        const now = Date.now();
        const elapsed = now - lastUpdateRef.current;
        if (elapsed < THROTTLE_INTERVAL) {
          scheduleRaf(); // too soon — wait for next frame
          return;
        }
        lastUpdateRef.current = now;
        startTransition(() => {
          getTabStore().mutateActiveTranscript((transcript) => {
            const index = transcript.patchAssistant(streamingTurnIdRef.current, (message) => (
              patchAssistantForStreaming({ ...message, isStreaming: true })
            ));
            if (index >= 0) streamingMessageIndexRef.current = index;
          });
        });
      });
    };
    return scheduleRaf;
  };

  const scheduleContentRaf = createStreamingRafScheduler(contentUpdateTimeoutRef, lastContentUpdateRef);
  const scheduleThinkingRaf = createStreamingRafScheduler(thinkingUpdateTimeoutRef, lastThinkingUpdateRef);

  /**
   * Recover streaming state when stream content arrives but we believe we are
   * idle.  `onStreamStart` is the authoritative start signal, but it can be
   * dropped (delivered before `window.onStreamStart` was registered, or lost on
   * a transient channel).  Because the coalescer also pushes message snapshots
   * via `updateMessages` independent of `streamingActive`, content would still
   * render while `streamingActive` stayed false — so the host looked busy but
   * the StatusPanel (and `finalizeTodosForSettledTurn`) behaved as if the turn
   * had settled, wrongly flipping `in_progress` todos to `completed`.  An
   * incoming content/thinking delta is undeniable proof the backend is
   * mid-stream, so we recover the streaming state here.
   */
  const ensureStreamingActive = (): void => {
    if (isStreamingRef.current) return;
    isStreamingRef.current = true;
    setStreamingActive(true);
    startStallWatchdog();
  };

  /**
   * Append a content delta to a tab's transcript.
   *
   * <p>Copy-on-write: the tab object is cloned but its messages array is
   * shared with whatever the active view may already hold. Mutating in place
   * would let React skip a re-render after the user switches to this tab (the
   * array reference never changed), silently dropping the deltas that arrived
   * in between.</p>
   */
  const appendDeltaToTab = (t: any, delta: string): void => {
    t.streamingActive = true;
    t.loading = true;
    t.transcript.appendDelta(delta, t.transcript.streamingAssistantTurn());
  };

  const onContentDelta = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const delta = String(payload?.delta ?? '');
    // An empty id is NOT "the active session". Routing a delta there is exactly
    // how two concurrently-streaming sessions swapped text.
    const targetSessionId = resolveSessionId(env?.sessionId ?? null, 'onContentDelta');
    if (targetSessionId === null) return;
    // The host names the owning tab: routing by tab id is exact.
    const targetTabId = env?.tabId ?? null;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    const ownerTabId = ownerTabFor(targetTabId, targetSessionId);
    if (hasTabs && !ownerTabId) return;
    if (ownerTabId && !tabStore.isActiveTab(ownerTabId)) {
      tabStore.updateTab(ownerTabId, (t: any) => {
        appendDeltaToTab(t, delta);
      });
      return;
    }

    if (isSessionTransitioning(targetSessionId)) return;
    ensureStreamingActive();
    window.__lastStreamActivityAt = Date.now();
    streamingContentRef.current += delta;
    scheduleContentRaf();
  };

  const onThinkingDelta = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const delta = String(payload?.delta ?? '');
    const targetSessionId = resolveSessionId(env?.sessionId ?? null, 'onThinkingDelta');
    if (targetSessionId === null) return;
    // The host names the owning tab: routing by tab id is exact.
    const targetTabId = env?.tabId ?? null;

    const markThinking = (t: any) => {
      t.streamingActive = true;
      t.loading = true;
      t.isThinking = true;
    };

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    const ownerTabId = ownerTabFor(targetTabId, targetSessionId);
    if (hasTabs && !ownerTabId) return;
    if (ownerTabId && !tabStore.isActiveTab(ownerTabId)) {
      tabStore.updateTab(ownerTabId, markThinking);
      return;
    }

    if (isSessionTransitioning(targetSessionId)) return;
    ensureStreamingActive();
    window.__lastStreamActivityAt = Date.now();
    streamingThinkingRef.current += delta;
    scheduleThinkingRaf();
  };

  // Mark any tool_use block that never received a tool_result as denied, so its
  // card stops spinning. Runs against the current message list; a no-op on a
  // fully-resolved turn. Used both on normal stream end and on the turn-ended-
  // without-a-stream paths (errors, non-streaming turns) so a failed turn never
  // leaves the agent's last tool hanging forever.
  const finalizeUnresolvedToolUses = () => {
    if (!window.__deniedToolIds) {
      window.__deniedToolIds = new Set<string>();
    }
    try {
      const transcript = getTabStore().getActiveTab().transcript;
      const interruptedIds = collectUnresolvedToolUseIds(transcript.getSnapshot());
      if (interruptedIds.length === 0) return;
      const denied = window.__deniedToolIds!;
      for (const id of interruptedIds) denied.add(id);
      // A fresh snapshot reference re-renders the now-denied tool cards.
      getTabStore().mutateActiveTranscript((current) => current.touch());
    } catch (error) {
      console.error('[Frontend] Failed to finalize unresolved tool ids:', error);
    }
  };

  /**
   * Re-dispatch a terminal that arrived while the UI was mid session-switch.
   *
   * The old code did `if (transitioning) return;` — a terminal
   * arriving during a switch was swallowed outright, which is one way a tab kept
   * showing a spinner long after its turn had ended. A terminal is never safe to
   * drop, so it is retried until the transition completes (bounded, so a stuck
   * transition flag cannot spin forever).
   */
  const TRANSITION_DEFER_MAX_MS = 5000;
  const deferUntilTransitionComplete = (sessionId: string | null, retry: () => void): void => {
    const startedAt = Date.now();
    const tick = (): void => {
      if (!isSessionTransitioning(sessionId) || Date.now() - startedAt > TRANSITION_DEFER_MAX_MS) {
        retry();
        return;
      }
      setTimeout(tick, 50);
    };
    setTimeout(tick, 50);
  };

  const onStreamEnd = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    let targetSessionId: string | null = env?.sessionId ?? null;
    const sequence = payload?.sequence as string | number | undefined;
    // The host names the owning tab: routing by tab id is exact.
    const targetTabId = env?.tabId ?? null;

    // A terminal with no identity is dropped rather than applied to the active
    // tab: applying it there is how stopping session B cleared session A's
    // spinner while leaving B's own tab spinning.
    targetSessionId = resolveSessionId(targetSessionId, 'onStreamEnd');
    if (targetSessionId === null) return;

    const finishTurn = (t: any, calculatedDurationMs?: number) => {
      t.streamingActive = false;
      t.loading = false;
      const streamEndedAt = Date.now();
      const durMs = calculatedDurationMs ?? (t.turnStartedAt ? streamEndedAt - t.turnStartedAt : undefined);
      t.turnStartedAt = null;

      t.transcript.settleAssistant(null, durMs);
      t.transcript.rebuildMetadata(reconstructTurnMetadata);
    };

    const tabStore = getTabStore();
    const ownerTabId = ownerTabFor(targetTabId, targetSessionId);
    // A terminal for a session no tab owns (a closed tab) has no spinner to
    // clear; applying it to the active tab would stop an unrelated turn.
    if (tabStore.size() > 0 && !ownerTabId) return;
    const isBackground = Boolean(ownerTabId) && !tabStore.isActiveTab(ownerTabId);
    if (isBackground) {
      const turnStartedAt = sessionTurnStartTimes.get(targetSessionId);
      sessionTurnStartTimes.delete(targetSessionId);
      const streamEndedAt = Date.now();
      const calculatedDurationMs = turnStartedAt ? streamEndedAt - turnStartedAt : undefined;

      const finishAndReturn = (t: any) => {
        finishTurn(t, calculatedDurationMs);
      };

      tabStore.updateTab(ownerTabId, finishAndReturn);
      // The turn is over: drop the runtime of that tab. Keeping it would make
      // the tab restore `isStreaming=true` the next time it is activated,
      // leaving its spinner stuck forever.
      tabStore.clearStreamingRuntimeFor(targetSessionId);
      return;
    }

    if (isSessionTransitioning(targetSessionId)) {
      deferUntilTransitionComplete(targetSessionId, () => {
        window.onStreamEnd?.(payload, env);
      });
      return;
    }

    // Idempotency guard: dual-path delivery (primary via flush callback +
    // fallback via Alarm) may send onStreamEnd twice for the same turn.
    // Only the first arrival takes effect; the second is a no-op.
    //
    // After the first onStreamEnd processes, streamingTurnIdRef is cleared to -1
    // and isStreamingRef is set to false. The second arrival sees these cleared
    // refs and should bail out. We check both conditions:
    // 1. If the current turn ID was already processed (before refs were cleared)
    // 2. If streaming is already inactive (refs were already cleared by first call)
    const currentTurnId = streamingTurnIdRef.current;
    const handlingMode = getStreamEndHandlingMode(
      options.currentProviderRef.current,
      isStreamingRef.current,
      currentTurnId,
    );
    if (currentTurnId > 0 && window.__streamEndProcessedTurnId === currentTurnId) {
      return;
    }
    if (handlingMode === 'skip') {
      // No active stream to finalize (refs already cleared by a prior
      // onStreamEnd, OR the turn never streamed — a non-streaming turn or one
      // that failed before [STREAM_START]). Either way, still mark any dangling
      // tool_use as denied: on an errored/aborted turn the tool_result never
      // arrives, and without this the last tool card spins forever. Idempotent
      // (a no-op when everything is already resolved or already denied).
      finalizeUnresolvedToolUses();
      return;
    }

    clearStallWatchdog();
    const parsedSequence = parseSequence(sequence);
    // Only update minAcceptedUpdateSequence for valid positive sequences.
    // The fallback path sends sequence=-1 which means "no sequence info" —
    // it should not participate in sequence tracking.
    if (parsedSequence != null && parsedSequence >= 0) {
      advanceMinAcceptedSequence(targetSessionId, parsedSequence);
    }
    // Notify backend about stream completion for tab status indicator
    sendWindowCommand('tab.status.changed', { status: 'completed' });

    // A terminal error row (quota/auth) is the only content of some turns.
    // The pending rAF snapshot must be applied before it is canceled, or the
    // error never reaches the transcript and the quota notice cannot render.
    const pendingSnapshotHasTerminalError = (): boolean => {
      if (typeof window.__pendingUpdateJson !== 'string' || window.__pendingUpdateJson.length === 0) {
        return false;
      }
      try {
        const pending = JSON.parse(window.__pendingUpdateJson) as Array<Record<string, unknown>>;
        return Array.isArray(pending) && pending.some((message) => message?.type === 'error');
      } catch {
        return false;
      }
    };

    if (handlingMode === 'minimal') {
      if (pendingSnapshotHasTerminalError()) {
        window.__flushPendingUpdateMessages?.(targetSessionId);
      } else if (typeof window.__cancelPendingUpdateMessages === 'function') {
        window.__cancelPendingUpdateMessages();
      }
      setStreamingActive(false);
      setLoading(false);
      setLoadingStartTime(null);
      setIsThinking(false);
      window.__streamEndProcessedTurnId = currentTurnId > 0 ? currentTurnId : undefined;
      return;
    }

    // FIX: Extract backend final snapshot from pending updateMessages BEFORE cancelling rAF.
    // The backend's final flush contains the authoritative message state (complete raw blocks).
    // If onStreamEnd cancels the rAF without processing this snapshot, the final message may
    // show incomplete content (e.g., last delta missing) or duplicated content in raw blocks.
    //
    // FIX: Also preserve tool_result user messages from the pending snapshot.
    // Previously only the assistant message was extracted; tool_result user messages were
    // silently dropped when the pending rAF was cancelled.  This caused tool cards to
    // remain stuck in "pending" state (spinner) even though the tool had completed.
    let backendSnapshotContent: string | undefined;
    let backendSnapshotRaw: ClaudeRawMessage | string | undefined = undefined;
    const pendingToolResultMsgs: Array<{ content: string; raw: Record<string, unknown> }> = [];
    if (typeof window.__pendingUpdateJson === 'string' && window.__pendingUpdateJson.length > 0) {
      try {
        const parsed = JSON.parse(window.__pendingUpdateJson) as Array<Record<string, unknown>>;
        for (let i = parsed.length - 1; i >= 0; i--) {
          const msg = parsed[i];
          // Stop scanning backwards if we cross a user prompt (which started this turn).
          // Any assistant messages before a user prompt belong to previous turns!
          if (msg?.type === 'user' && (!msg.content || (typeof msg.content === 'string' && msg.content.trim() !== '[tool_result]'))) {
            break;
          }
          if (msg?.type === 'assistant') {
            const rawContent = msg.content;
            const content = typeof rawContent === 'string' ? rawContent : '';
            if (content) {
              backendSnapshotContent = content;
            }
            const rawVal = msg.raw;
            if (rawVal != null && (typeof rawVal === 'object' || typeof rawVal === 'string')) {
              backendSnapshotRaw = rawVal as ClaudeRawMessage | string;
            }
            break;
          }
        }
        // Collect tool_result user messages from the pending snapshot so that
        // completed tool calls are not lost when the rAF is cancelled below.
        for (let i = 0; i < parsed.length; i++) {
          const msg = parsed[i];
          if (msg?.type === 'user' && typeof msg.content === 'string' && msg.content.trim() === '[tool_result]') {
            const raw = msg.raw as Record<string, unknown> | undefined;
            if (raw != null && typeof raw === 'object') {
              pendingToolResultMsgs.push({ content: '[tool_result]', raw });
            }
          }
        }
      } catch (error) {
        // __pendingUpdateJson is produced internally by the bridge; a parse failure
        // indicates an upstream contract violation worth surfacing for diagnosis.
        cardDebugLog('[Frontend] Failed to parse __pendingUpdateJson on stream end:', error);
      }
    }

    if (pendingSnapshotHasTerminalError()) {
      window.__flushPendingUpdateMessages?.(targetSessionId);
    } else if (typeof window.__cancelPendingUpdateMessages === 'function') {
      window.__cancelPendingUpdateMessages();
    }

    // Clear pending rAF callbacks — their content is already in streamingContentRef
    if (contentUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(contentUpdateTimeoutRef.current);
      contentUpdateTimeoutRef.current = null;
    }
    if (thinkingUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(thinkingUpdateTimeoutRef.current);
      thinkingUpdateTimeoutRef.current = null;
    }

    // Snapshot keys that need collapsing BEFORE they are cleared inside the updater.
    const keysToCollapse = new Set(autoExpandedThinkingKeysRef.current);

    // Snapshot turn start time BEFORE entering the updater
    const currentActiveSessionId = targetSessionId
      ?? tabStore.getActiveSessionId()
      ?? options.currentSessionIdRef?.current; // legacy-allow: a window with no tab strip has only the view's session
    const turnStartedAt = window.__turnStartedAt ?? (currentActiveSessionId ? sessionTurnStartTimes.get(currentActiveSessionId) : undefined);
    window.__turnStartedAt = undefined;
    if (currentActiveSessionId) {
      sessionTurnStartTimes.delete(currentActiveSessionId);
    }
    // Snapshot stream-end time and derived values BEFORE the updater to keep it pure.
    // Date.now() / new Date() inside an updater are impure: React StrictMode double-invokes
    // updaters and would produce two different timestamps for the same stream end.
    const streamEndedAt = Date.now();
    const durationMs = (typeof turnStartedAt === 'number' && turnStartedAt > 0)
      ? streamEndedAt - turnStartedAt
      : undefined;
    const toolResultTimestamp = new Date(streamEndedAt).toISOString();

    // Snapshot streaming state BEFORE clearing refs - used for post-stream merge guard
    const endedStreamingTurnId = streamingTurnIdRef.current;

    // Initialize denied tool ids set if not exists
    if (!window.__deniedToolIds) {
      window.__deniedToolIds = new Set<string>();
    }
    // FIX: Prioritize streaming content over backend snapshot to prevent digit loss
    // Streaming content has all the latest deltas (including the final one just flushed).
    // Backend snapshot might be from an earlier coalescer push and may be incomplete.
    const endedStreamingContent = streamingContentRef.current || backendSnapshotContent || '';
    const endedBackendRaw = backendSnapshotRaw;

    // FIX: Clear streaming refs BEFORE setMessages updater to prevent race conditions.
    //
    // Trade-off analysis:
    // - Original approach: refs cleared inside updater, leverages React batching to ensure
    //   clearing and state update happen together. But this caused timing issues when
    //   deferred operations (rAF, timeout) executed after the updater but before refs were
    //   actually cleared, allowing them to modify the streaming message incorrectly.
    // - New approach: refs cleared outside updater, uses snapshot values inside updater.
    //   This prevents race conditions where deferred updateMessages sees isStreamingRef=false
    //   but streamingMessageIndexRef still points to the old message.
    // - Benefit: More robust handling of async callback ordering, especially important
    //   when JCEF async chains can reorder callbacks unpredictably.
    // - Risk: Minimal, since snapshot values are used inside updater and refs are cleared
    //   synchronously before the updater is scheduled.
    //
    // Streaming state refs (isStreaming flag)
    isStreamingRef.current = false;
    useBackendStreamingRenderRef.current = false;

    // Index refs (message position tracking)
    streamingMessageIndexRef.current = -1;
    streamingTurnIdRef.current = -1;

    // Content buffer refs
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    autoExpandedThinkingKeysRef.current.clear();

    // Drop the archived runtime for this session too. The refs above only cover
    // the *live* copy; if this tab was activated after being archived, a stale
    // snapshot would reinstate `isStreaming=true` on the next switch.
    getTabStore().clearStreamingRuntimeFor(targetSessionId);

    // Mark that streaming just ended - used by mergeConsecutiveAssistantMessages to
    // distinguish recently-ended streaming messages from true history messages.
    window.__lastStreamEndedTurnId = endedStreamingTurnId;
    window.__lastStreamEndedAt = Date.now();

    // Flush final content and finalize the streaming message.
    // FIX (Issue #1315): Merge interrupted tool detection AND the denied-tool side
    // effect into this single setMessages call.
    //
    // Context: React 18 batches multiple setState calls within the same event handler.
    // Each updater receives the SAME previous state, and React uses only the LAST
    // return value for the final state. Previously a second setMessages() that
    // scanned for interrupted tool_use IDs received stale state and overwrote the
    // updates made here.
    //
    // CRITICAL: the __deniedToolIds mutation MUST also happen inside the updater,
    // not after setMessages() returns. React 18 does NOT run updaters synchronously
    // during the setState call — they run later during the render phase. Any value
    // captured in a closure variable after setMessages() would be empty.
    const transcript = getTabStore().getActiveTab().transcript;
    getTabStore().mutateActiveTranscript((current) => {
      current.finalizeStreamingTurn(
        {
          turnId: endedStreamingTurnId,
          content: endedStreamingContent,
          backendRaw: endedBackendRaw,
          durationMs,
          pendingToolResults: pendingToolResultMsgs,
          toolResultTimestamp,
        },
        (messages) => reconstructTurnMetadata(messages, { skipTrailingTurn: false }),
      );
    });

    // Interrupted tools are read from the settled transcript, so a card whose
    // tool_use never received a result stops spinning instead of outliving the
    // turn. The denied set is idempotent.
    try {
      const interruptedIds = collectUnresolvedToolUseIds(transcript.getSnapshot());
      const deniedToolIds = window.__deniedToolIds!;
      for (const id of interruptedIds) deniedToolIds.add(id);
    } catch (error) {
      console.error('[Frontend] Failed to collect interrupted tool ids:', error);
    }

    // Collapse auto-expanded thinking blocks using the pre-clear snapshot
    if (setExpandedThinking && keysToCollapse.size > 0) {
      setExpandedThinking((prev) => {
        const next = { ...prev };
        keysToCollapse.forEach((key) => {
          next[key] = false;
        });
        return next;
      });
    }

    // React state (not ref) — React batches this with setMessages automatically
    setStreamingActive(false);

    // FIX: onStreamEnd is the authoritative signal that streaming has ended.
    // Reset loading state here to prevent race conditions where showLoading("false")
    // arrives before onStreamEnd and gets ignored by the isStreamingRef guard,
    // while the flush callback's showLoading("false") may be delayed or lost
    // (e.g., due to slow message serialization or multi-hop async chains).
    setLoading(false);
    setLoadingStartTime(null);
    setIsThinking(false);

    // Mark this turn as processed — idempotency guard for dual-path delivery
    window.__streamEndProcessedTurnId = endedStreamingTurnId;
  };

  // Permission denied callback — kept as a no-op for backward compatibility.
  //
  // The backend (OpencodeBuddyChatWindow.interruptDueToPermissionDenial) always calls
  // onStreamEnd() immediately after this in the same EDT invokeLater block.
  // The merged onStreamEnd updater performs the interrupted-tool scan against
  // the authoritative finalized state, which supersedes anything this handler
  // could do against pre-finalize state. Running a second setMessages here
  // would only produce a wasted re-render (React 18 batches the two calls, and
  // onStreamEnd is the last writer). See Issue #1315 investigation for details.
  const onPermissionDenied = () => {};

  // Block reset callback — clears streaming content refs when a new assistant
  // message starts within an ongoing stream (e.g., after tool_use loop iteration).
  // This prevents cross-turn content merging where new thinking/text deltas
  // would append to previous turn's buffered content.
  const onBlockReset = (_payload?: HostEventPayload, env?: HostEventEnvelope) => {
    const targetSessionId = env?.sessionId ?? null;
    const tabStore = getTabStore();
    const ownerTabId = ownerTabFor(null, targetSessionId);
    const isBackground = Boolean(ownerTabId) && !tabStore.isActiveTab(ownerTabId);
    if (isBackground) {
      return;
    }
    if (!isStreamingRef.current) {
      // Stream not active, ignore (could be stale signal after stream ended)
      return;
    }
    // NOTE: content/thinking buffers are intentionally NOT cleared here.
    // The Java layer keeps ONE assistant message for the whole turn (including
    // every tool_use loop iteration), appending each turn's text/thinking as
    // additional raw blocks. Clearing the buffers on BLOCK_RESET would discard
    // the prefix carried by earlier turns and break sync*BlocksWithContent's
    // prefix reconciliation: a multi-block turn would drop new deltas (prefix
    // no longer matches) and a single-block turn would overwrite the prior
    // turn's block with the new turn's content. Keep the cumulative buffer; the
    // sync functions' trailing-block guard routes each turn's content into its
    // own block once the backend snapshot delivers it.
    // Intentionally NOT resetting streamingMessageIndexRef either: the assistant
    // message is shared across turns, so the index already points at it.
    // Reset throttle timeouts to ensure clean state for new deltas
    if (contentUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(contentUpdateTimeoutRef.current);
      contentUpdateTimeoutRef.current = null;
    }
    if (thinkingUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(thinkingUpdateTimeoutRef.current);
      thinkingUpdateTimeoutRef.current = null;
    }
    // Reset last update timestamps to prevent throttle delays
    lastContentUpdateRef.current = 0;
    lastThinkingUpdateRef.current = 0;
    // Clear auto-expanded thinking keys for the new turn
    autoExpandedThinkingKeysRef.current.clear();
  };

  const onStreamingHeartbeat = (_payload?: HostEventPayload, env?: HostEventEnvelope) => {
    const targetSessionId = env?.sessionId ?? null;
    const tabStore = getTabStore();
    const ownerTabId = ownerTabFor(null, targetSessionId);
    const isBackground = Boolean(ownerTabId) && !tabStore.isActiveTab(ownerTabId);
    if (isBackground) {
      return;
    }
    window.__lastStreamActivityAt = Date.now();
  };

  // ── [TURN_END]: the daemon's authoritative, per-session turn terminal ──────
  //
  // `onStreamEnd` is tied to one adapter's flush pipeline and is therefore
  // suppressible: it can be swallowed by a session transition, lost when the
  // serve process dies mid-turn, or never emitted for a turn that errored before
  // it streamed. All three left a tab showing a spinner that nobody would ever
  // clear — the third symptom.
  //
  // The daemon now emits [TURN_END] whenever a turn settles, for every session,
  // carrying the session id and a turnId taken from the server's own
  // `durable.seq` (monotonic per session, so no client-side counter to skew).
  // Keyed on (sessionId, turnId) this is idempotent, so it is safe for it to
  // arrive alongside — or instead of — onStreamEnd.
  const processedTurnEndKeys = new Set<string>();

  const onTurnEnd = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const sessionId = resolveSessionId(env?.sessionId ?? null, 'onTurnEnd');
    if (sessionId === null) return;
    const json = readJson<{ turnId?: string | number }>(payload?.terminal);
    const tabId = env?.tabId ?? null;

    let turnId: string | number | undefined;
    if (json && typeof json === 'object') {
      turnId = json.turnId;
    }

    const key = `${sessionId}#${turnId ?? 'none'}`;
    if (processedTurnEndKeys.has(key)) return;
    processedTurnEndKeys.add(key);
    // Bounded: a long-lived window must not accumulate these forever.
    if (processedTurnEndKeys.size > 256) {
      processedTurnEndKeys.clear();
    }

    const tabStore = getTabStore();
    // The host names the owning tab: routing by tab id is exact.
    const targetTabId = typeof tabId === 'string' && tabId.length > 0 ? tabId : null;
    const settleTurn = (t: any) => {
      t.streamingActive = false;
      t.loading = false;
      t.isThinking = false;
    };
    const hasTabs = tabStore.size() > 0;
    const ownerTabId = ownerTabFor(targetTabId, sessionId);
    if (hasTabs && !ownerTabId) return;
    if (ownerTabId && !tabStore.isActiveTab(ownerTabId)) {
      tabStore.updateTab(ownerTabId, settleTurn);
      return;
    }

    clearStallWatchdog();
    setStreamingActive(false);
    setLoading(false);
    setLoadingStartTime(null);
    setIsThinking(false);
    // A turn that ended without its tool_result never gets one now.
    finalizeUnresolvedToolUses();
  };

  // The semantic catalog is the routing table.
  registerHostEvents({
    'message.block.reset': onBlockReset,
    'content.delta': onContentDelta,
    'turn.ended': onStreamEnd,
    'turn.started': onStreamStart,
    'thinking.delta': onThinkingDelta,
    'turn.heartbeat': onStreamingHeartbeat,
    'permission.denied': onPermissionDenied,
    'turn.settled': onTurnEnd,
  });
}
