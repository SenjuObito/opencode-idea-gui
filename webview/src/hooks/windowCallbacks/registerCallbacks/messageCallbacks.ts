/**
 * messageCallbacks.ts
 *
 * Registers window bridge callbacks for message management:
 * updateMessages, updateStatus, showLoading, showThinkingStatus,
 * setHistoryData, clearMessages, addErrorMessage, addHistoryMessage,
 * historyLoadComplete, addUserMessage.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { ClaudeMessage } from '../../../types';
import type { ContextUsageData } from '../../../components/ContextUsageDialog';
import { sendWindowCommand, cardDebugLog } from '../../../utils/bridge';
import { registerHostEvents } from '../../../utils/hostEventRegistry';
import { isSessionTransitioning } from '../tabTransition';
import type { HostEventEnvelope, HostEventPayload } from '../../../utils/hostEventTypes';
import { debugError } from '../../../utils/debug';
import { resolveSessionId } from '../../../utils/sessionIdentity';
import { stripDuplicateTrailingToolMessages } from '../messageSync';
import { activeTabId, endTransition, owningTabIdForSession, transitionTargetFor, isTabTransitioning } from '../tabTransition';
import {
  parseSequence,
  getMinAcceptedSequence,
  advanceMinAcceptedSequence,
  resetMinAcceptedSequence,
} from '../sequenceBarrier';
import { reconstructTurnMetadata } from '../../../utils/turnMetadataReconstruction';
import { collectUnresolvedToolUseIds } from './streamingCallbacks';
import i18n from '../../../i18n/config';
import { getTabStore } from '../../../store/tabStore';
import { parseHistoryWindow } from '../../../store/historyWindow';

const isTruthy = (v: unknown) => v === true || v === 'true';

/**
 * Read the host's live draft from a message frame.
 *
 * <p>The IDEA host serializes it as an object beside `messages`; a test or an
 * older producer may send the same object as a JSON string. Both describe the
 * same fact, so the callback accepts either shape.</p>
 */
function parseLiveDraft(payload: HostEventPayload | undefined): ClaudeMessage | null {
  const value = payload?.live;
  if (value == null) return null;
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value) as unknown;
      return parsed && typeof parsed === 'object' ? parsed as ClaudeMessage : null;
    } catch {
      return null;
    }
  }
  return typeof value === 'object' ? value as ClaudeMessage : null;
}

export function registerMessageCallbacks(
  options: UseWindowCallbacksOptions,
  resetTransientUiState: () => void,
  requestHistoryRenderCommit: (refreshEpoch: number) => void,
): void {
  const {
    addToast,
    setStatus,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    setHistoryData,
    userPausedRef,
    isUserAtBottomRef,
    messagesContainerRef,
    suppressNextStatusToastRef,
    streamingContentRef,
    streamingThinkingRef,
    isStreamingRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    findLastAssistantIndex,
    patchAssistantForStreaming,
    updateContextUsageData,
    closeContextUsageDialog,
    currentSessionIdRef,
    setStreamingActive,
  } = options;

  // During streaming, buffer updateMessages calls and process only the latest
  // one per animation frame. This prevents JSON.parse of large payloads from
  // blocking the main thread on every coalescer push (which can arrive every
  // 50ms), eliminating the "fake freeze" symptom.
  //
  // Stored on `window` so that if registerMessageCallbacks is called again
  // (e.g., HMR, parent re-render), the previous pending rAF is cancelled
  // first — preventing stale closures from executing.
  if (window.__pendingUpdateRaf != null) {
    clearTimeout(window.__pendingUpdateRaf);
    window.__pendingUpdateRaf = null;
    window.__pendingUpdateJson = null;
    window.__pendingUpdateSequence = null;
  }
  let pendingUpdateJson: string | null = null;
  let pendingUpdateRaf: number | null = null;
  let pendingUpdateSequence: number | null = null;
  let pendingUpdateBaseIndex: string | number | null | undefined = null;
  let pendingUpdateTabId: string | null = null;
  let pendingUpdateLive: ClaudeMessage | null = null;
  // Expose a cancellation function so onStreamEnd can cancel stale rAF-deferred
  // updateMessages calls, preventing them from overwriting the final state after
  // streaming refs are cleared.
  const cancelPendingUpdateMessages = () => {
    if (pendingUpdateRaf !== null) {
      clearTimeout(pendingUpdateRaf);
    }
    pendingUpdateRaf = null;
    pendingUpdateJson = null;
    pendingUpdateSequence = null;
    pendingUpdateBaseIndex = null;
    pendingUpdateTabId = null;
    pendingUpdateLive = null;
    window.__pendingUpdateRaf = null;
    window.__pendingUpdateJson = null;
    window.__pendingUpdateSequence = null;
  };
  window.__cancelPendingUpdateMessages = cancelPendingUpdateMessages;

  const requestNativeHistoryRefresh = () => {
    const refreshEpoch = (window.__historySurfaceRefreshEpoch ?? 0) + 1;
    window.__historySurfaceRefreshEpoch = refreshEpoch;
    requestHistoryRenderCommit(refreshEpoch);
  };

  const refreshRestoredHistoryIfPending = (acceptedMessageCount: number) => {
    if (window.__pendingHistoryRefreshMessageCount !== acceptedMessageCount) {
      return;
    }
    window.__pendingHistoryRefreshMessageCount = undefined;
    requestNativeHistoryRefresh();
  };

  /**
   * Apply a full snapshot to a tab that is not on screen.
   *
   * <p>Routing is by identity: a tab that is not the active one still keeps its
   * own last snapshot. "Not on screen" only means "do not touch the view", not
   * "discard the data" — and not "discard the rows the host has not echoed
   * yet", so the tab's optimistic user bubble and its streaming assistant are
   * carried across the snapshot.</p>
   */
  /** Write a full snapshot to a tab. The transcript owns the merge. */
  const writeSnapshotToTab = (tabId: string, json: string, baseIndexArg?: string | number | null, live: ClaudeMessage | null = null): boolean => (
    getTabStore().mutateTranscript(tabId, (transcript) => {
      try {
        const raw = JSON.parse(json) as unknown;
        if (!Array.isArray(raw)) return;
        const base = typeof baseIndexArg === 'number' ? baseIndexArg : Number(baseIndexArg);
        transcript.applySnapshot(raw as ClaudeMessage[], Number.isSafeInteger(base) ? base : 0, live);
        // Background tabs have no delta buffers; discard the boundary flag so it
        // cannot reset the active tab later.
        transcript.consumeCompactionBoundary();
      } catch (error) {
        console.error('[MultiSession] Failed to parse background updateMessages', error);
      }
    })
  );

  /** Append a tail page to a tab. */
  const writeTailToTab = (
    tabId: string,
    json: string,
    baseIndexArg?: string | number | null,
    live: ClaudeMessage | null = null,
  ): boolean => (
    getTabStore().mutateTranscript(tabId, (transcript) => {
      try {
        const raw = JSON.parse(json) as unknown;
        if (!Array.isArray(raw)) return;
        const base = typeof baseIndexArg === 'number' ? baseIndexArg : Number(baseIndexArg);
        transcript.applyTail(raw as ClaudeMessage[], Number.isSafeInteger(base) ? base : 0, live);
        transcript.consumeCompactionBoundary();
      } catch (error) {
        console.error('[MultiSession] Failed to parse background updateMessageTail', error);
      }
    })
  );

  /**
   * Is this frame held back by a session switch in the tab it addresses?
   *
   * <p>The guard is per tab: a background tab's turn must not be dropped
   * because another tab is switching. A frame with no session id is the
   * incoming conversation — the tab is waiting for exactly that — so it is
   * allowed; a bound frame must name the transition target.</p>
   */
  const isBlockedByTabTransition = (
    targetSessionId: string | null,
    addressTabId: string | null,
  ): boolean => {
    if (!addressTabId) return isSessionTransitioning(targetSessionId);
    if (!isTabTransitioning(addressTabId)) return false;
    if (!targetSessionId) return false;
    return targetSessionId !== transitionTargetFor(addressTabId);
  };

  const processUpdateMessages = (
    json: string,
    sequence: number | null = null,
    baseIndexArg?: string | number | null,
    targetSessionId?: string | null,
    targetTabId?: string | null,
    live: ClaudeMessage | null = null,
  ) => {
    const effectiveSessionId = targetSessionId ?? null;
    // Address by tab first: the host names the tab, and that name exists before
    // the session id does. The session's owner is only a fallback for events
    // that predate tab addressing.
    const tabStore = getTabStore();
    const addressTabId = targetTabId ?? owningTabIdForSession(targetSessionId);
    cardDebugLog('[processUpdateMessages] called, seq:', sequence, 'isStreaming:', isStreamingRef.current, 'jsonLen:', json.length, 'tab:', addressTabId, 'transitioning:', isBlockedByTabTransition(effectiveSessionId, addressTabId));
    // Re-check the session-transition guard inside processUpdateMessages so the
    // rAF-deferred path (window.updateMessages → setTimeout → processUpdateMessages)
    // cannot resurrect cleared messages when a transition starts between the
    // entry-point check and the deferred fire. This also catches synchronous
    // callers that bypass the entry point — addHistoryMessage / addUserMessage
    // already guard, but processUpdateMessages is the canonical setter and
    // should be self-defending.
    if (isBlockedByTabTransition(effectiveSessionId, addressTabId)) {
      // The tab is switching sessions: only the session it is switching *to*
      // may write. Events of the outgoing session are dropped.
      cardDebugLog('[processUpdateMessages] BLOCKED: tab is transitioning');
      return;
    }
    // A frame deferred 16ms during streaming can outlive a tab switch. Identity
    // decides the sink: the addressed tab keeps its own snapshot; a frame no
    // tab claims (a closed tab) is dropped.
    if (tabStore.size() > 0 && !addressTabId) {
      cardDebugLog('[processUpdateMessages] dropped: no tab owns', targetSessionId ?? targetTabId);
      return;
    }
    if (addressTabId && !tabStore.isActiveTab(addressTabId)) {
      writeSnapshotToTab(addressTabId, json, baseIndexArg, live);
      return;
    }
    const minAcceptedSequence = getMinAcceptedSequence(effectiveSessionId);
    if (sequence != null && sequence < minAcceptedSequence) {
      cardDebugLog('[processUpdateMessages] BLOCKED: seq', sequence, '< minAccepted', minAcceptedSequence, 'sessionId:', effectiveSessionId);
      return;
    }

    try {
      // The snapshot is a host fact: the transcript owns how it lands. Older
      // pages already read into this tab stay in front of it, and messages the
      // host has not echoed (optimistic bubbles, the streaming assistant) live
      // in the transcript's own segments, so a snapshot cannot erase them.
      const raw = JSON.parse(json) as unknown;
      if (!Array.isArray(raw)) return;
      const backendMessages = raw as ClaudeMessage[];
      if (sequence != null) {
        advanceMinAcceptedSequence(effectiveSessionId, sequence);
      }

      const parsedBase = baseIndexArg != null && baseIndexArg !== '' ? Number(baseIndexArg) : null;
      const hasWindowBase = parsedBase != null && Number.isSafeInteger(parsedBase) && parsedBase >= 0;
      if (live) {
        window.__pendingLiveDraftJson = JSON.stringify(live);
      }

      getTabStore().mutateActiveTranscript((transcript) => {
        transcript.applySnapshot(backendMessages, hasWindowBase ? (parsedBase as number) : 0, live);
        // A boundary seals the pre-compaction bubble into remote. The delta
        // buffers still describe that old segment, so drop them before the
        // first post-compaction snapshot merges into a new bubble.
        if (transcript.consumeCompactionBoundary()) {
          streamingContentRef.current = '';
          streamingThinkingRef.current = '';
          streamingMessageIndexRef.current = -1;
        }
        transcript.rebuildMetadata((list) => stripDuplicateTrailingToolMessages(
          reconstructTurnMetadata(list, { skipTrailingTurn: isStreamingRef.current }),
          options.currentProviderRef.current,
        ));
        if (!isStreamingRef.current) return;
        const currentTurnId = streamingTurnIdRef.current;
        const index = transcript.patchAssistant(currentTurnId, (message) => {
          const backendContent = typeof message.content === 'string' ? message.content : '';
          // The delta buffer can be ahead of the coalesced snapshot; never let
          // the snapshot roll visible text backwards.
          if (streamingContentRef.current && streamingContentRef.current.length >= backendContent.length) {
            return patchAssistantForStreaming({
              ...message,
              content: streamingContentRef.current,
              isStreaming: true,
              __turnId: currentTurnId,
            });
          }
          if (backendContent) streamingContentRef.current = backendContent;
          return { ...message, isStreaming: true, __turnId: currentTurnId };
        });
        if (index >= 0) streamingMessageIndexRef.current = index;
      });

      window.__lastAcceptedMessageCount = backendMessages.length;
      refreshRestoredHistoryIfPending(backendMessages.length);
    } catch (error) {
      console.error('[Frontend] Failed to parse messages:', error);
    }
  };

  const processMessageTail = (
    json: string,
    baseIndexArg: string | number,
    sequenceArg?: string | number,
    targetSessionId?: string | null,
    targetTabId?: string | null,
    live: ClaudeMessage | null = null,
  ) => {
    const effectiveSessionId = targetSessionId ?? null;
    const tabStore = getTabStore();
    const addressTabId = targetTabId ?? owningTabIdForSession(targetSessionId);
    if (isBlockedByTabTransition(effectiveSessionId, addressTabId)) return;
    // Same deferred-frame rule as processUpdateMessages: write the owning tab,
    // never the active view, and drop only when no tab claims the frame.
    if (tabStore.size() > 0 && !addressTabId) {
      cardDebugLog('[processMessageTail] dropped: no tab owns', targetSessionId ?? targetTabId);
      return;
    }
    if (addressTabId && !tabStore.isActiveTab(addressTabId)) {
      writeTailToTab(addressTabId, json, baseIndexArg, live);
      return;
    }
    const sequence = parseSequence(sequenceArg);
    const minAcceptedSequence = getMinAcceptedSequence(effectiveSessionId);
    if (sequence != null && sequence < minAcceptedSequence) return;

    if (isStreamingRef.current && window.__lastStreamActivityAt !== undefined) {
      window.__lastStreamActivityAt = Date.now();
    }

    const baseIndex = typeof baseIndexArg === 'number'
      ? baseIndexArg
      : Number(baseIndexArg);
    if (!Number.isSafeInteger(baseIndex) || baseIndex < 0) return;

    try {
      const tail = JSON.parse(json) as ClaudeMessage[];
      if (!Array.isArray(tail)) return;
      if (sequence != null) {
        advanceMinAcceptedSequence(effectiveSessionId, sequence);
      }
      if (live) {
        window.__pendingLiveDraftJson = JSON.stringify(live);
      }

      getTabStore().mutateActiveTranscript((transcript) => {
        transcript.applyTail(tail, baseIndex, live);
        if (transcript.consumeCompactionBoundary()) {
          streamingContentRef.current = '';
          streamingThinkingRef.current = '';
          streamingMessageIndexRef.current = -1;
        }
        // Turn footers (duration, usage) are derived from the tail's records;
        // while a turn is still streaming its footer is left for the settle path.
        transcript.rebuildMetadata((messages) => reconstructTurnMetadata(
          messages,
          { skipTrailingTurn: isStreamingRef.current },
        ));
        if (!isStreamingRef.current) return;
        const currentTurnId = streamingTurnIdRef.current;
        const index = transcript.patchAssistant(currentTurnId, (message) => {
          const backendContent = typeof message.content === 'string' ? message.content : '';
          if (streamingContentRef.current && streamingContentRef.current.length >= backendContent.length) {
            return patchAssistantForStreaming({
              ...message,
              content: streamingContentRef.current,
              isStreaming: true,
              __turnId: currentTurnId,
            });
          }
          if (backendContent) streamingContentRef.current = backendContent;
          return { ...message, isStreaming: true, __turnId: currentTurnId };
        });
        if (index >= 0) streamingMessageIndexRef.current = index;
      });
    } catch (error) {
      console.error('[Frontend] Failed to parse message tail:', error);
    }
  };

  const updateMessages = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    // A named event: the content is in the payload, the identity in the envelope.
    const targetSessionId: string | null = env?.sessionId ?? null;
    const json = String(payload?.messages ?? '[]');
    const sequenceArg = payload?.sequence as string | number | undefined;
    const baseIndexArg = payload?.baseIndex as string | number | undefined;
    const targetTabId: string | null = env?.tabId ?? null;
    const live = parseLiveDraft(payload);

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    // Address by tab first. The tab id is the durable identity of the
    // conversation: it exists before the daemon reports the session id, which is
    // exactly the window in which the first snapshot of a new tab arrives. The
    // session's owner is the fallback for events that predate tab addressing.
    const addressTabId = targetTabId ?? owningTabIdForSession(targetSessionId);

    if (hasTabs && addressTabId && !tabStore.isActiveTab(addressTabId)) {
      // The tab is not on screen: write its store, never the view. A closed tab
      // has nowhere to write and updates nothing.
      writeSnapshotToTab(addressTabId, json, baseIndexArg, live);
      return;
    }
    if (hasTabs && !addressTabId) {
      // No tab claims this frame: the user closed it (or the host pushed an
      // identity the webview never bound). Falling through to the active path
      // would render another conversation's messages in the tab on screen.
      cardDebugLog('[updateMessages] dropped: no tab owns', targetSessionId ?? targetTabId);
      return;
    }

    // During session transition, ignore message updates from stale session
    // callbacks to prevent cleared messages from being restored
    cardDebugLog('[updateMessages] called, transitioning:', isBlockedByTabTransition(targetSessionId, addressTabId), 'seq:', sequenceArg, 'jsonLen:', json?.length);
    if (isBlockedByTabTransition(targetSessionId, addressTabId)) {
      cardDebugLog('[updateMessages] BLOCKED: tab is transitioning');
      return;
    }
    const effectiveSessionId = targetSessionId ?? null;
    const sequence = parseSequence(sequenceArg);
    const minAcceptedSequence = getMinAcceptedSequence(effectiveSessionId);
    if (sequence != null && sequence < minAcceptedSequence) {
      return;
    }

    // FIX: Bump stream stall watchdog — receiving updateMessages proves the
    // backend→frontend bridge is alive even between content deltas (e.g.,
    // during tool execution phases where no text is produced).
    if (isStreamingRef.current && window.__lastStreamActivityAt !== undefined) {
      window.__lastStreamActivityAt = Date.now();
    }

    // During streaming, coalesce rapid updateMessages calls into one-per-frame.
    // The backend coalescer may push every 50ms; JSON.parse of large payloads
    // (100KB+ for long conversations) blocks the main thread and causes dropped
    // frames ("fake freeze"). Deferring to rAF ensures we only parse the latest
    // payload and yield to the browser between frames.
    if (isStreamingRef.current) {
      pendingUpdateJson = json;
      pendingUpdateSequence = sequence;
      pendingUpdateBaseIndex = baseIndexArg ?? null;
      pendingUpdateTabId = addressTabId;
      pendingUpdateLive = live;
      window.__pendingUpdateJson = json;
      window.__pendingUpdateSequence = sequence;
      if (live) {
        window.__pendingLiveDraftJson = JSON.stringify(live);
      }
      if (pendingUpdateRaf === null) {
        const timerId = setTimeout(() => {
          // The test environment can tear down the jsdom window before this
          // 16 ms coalescing tick fires. Never let a stale frame touch a
          // missing window.
          if (typeof window === 'undefined') return;
          pendingUpdateRaf = null;
          window.__pendingUpdateRaf = null;
          const latestJson = pendingUpdateJson;
          const latestSequence = pendingUpdateSequence;
          const latestBaseIndex = pendingUpdateBaseIndex;
          const latestTabId = pendingUpdateTabId ?? targetTabId;
          const latestLive = pendingUpdateLive;
          pendingUpdateJson = null;
          pendingUpdateSequence = null;
          pendingUpdateBaseIndex = null;
          pendingUpdateTabId = null;
          pendingUpdateLive = null;
          window.__pendingUpdateJson = null;
          window.__pendingUpdateSequence = null;
          // A session transition may have begun while this frame was buffered.
          // processUpdateMessages re-checks the transition guard itself now, so
          // this early return is defense-in-depth — without either check, a
          // stale snapshot deferred during the outgoing session's streaming
          // would run down the non-streaming path (isStreamingRef was cleared
          // by beginSessionTransition) and resurrect the cleared messages.
          if (isBlockedByTabTransition(targetSessionId, latestTabId)) {
            return;
          }
          if (latestJson) {
            processUpdateMessages(latestJson, latestSequence, latestBaseIndex, targetSessionId, latestTabId, latestLive);
          }
        }, 16);
        pendingUpdateRaf = timerId as unknown as number;
        window.__pendingUpdateRaf = timerId as unknown as number;
      }
      return;
    }

    processUpdateMessages(json, sequence, baseIndexArg, targetSessionId, targetTabId, live);
  };

  const flushPendingUpdateMessages = (targetSessionId?: string | null) => {
    const json = pendingUpdateJson ?? window.__pendingUpdateJson ?? null;
    const sequence = pendingUpdateSequence ?? null;
    const baseIndex = pendingUpdateBaseIndex;
    const tabId = pendingUpdateTabId;
    const live = pendingUpdateLive;
    if (typeof json === 'string' && json.length > 0) {
      // Force the non-buffering path: this is a terminal flush, so the
      // snapshot must be applied now rather than scheduled into another rAF
      // that cancelPendingUpdateMessages is about to clear.
      const wasStreaming = isStreamingRef.current;
      isStreamingRef.current = false;
      try {
        processUpdateMessages(
          json,
          sequence,
          baseIndex,
          targetSessionId ?? null,
          tabId,
          live,
        );
      } finally {
        isStreamingRef.current = wasStreaming;
      }
    }
    cancelPendingUpdateMessages();
  };
  window.__flushPendingUpdateMessages = flushPendingUpdateMessages;

  const updateMessageTail = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const targetTabId: string | null = env?.tabId ?? null;
    const json = String(payload?.messages ?? '[]');
    const baseIndexArg = payload?.baseIndex as string | number;
    const sequenceArg = payload?.sequence as string | number | undefined;
    const live = parseLiveDraft(payload);

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    // Tab first: the host names the tab on every tail frame, and that name is
    // valid before the session id is bound.
    const addressTabId = targetTabId ?? owningTabIdForSession(targetSessionId);
    if (hasTabs && addressTabId && !tabStore.isActiveTab(addressTabId)) {
      writeTailToTab(addressTabId, json, baseIndexArg, live);
      return;
    }
    if (hasTabs && !addressTabId) {
      cardDebugLog('[updateMessageTail] dropped: no tab owns', targetSessionId ?? targetTabId);
      return;
    }
    if (live) {
      window.__pendingLiveDraftJson = JSON.stringify(live);
    }

    processMessageTail(json, baseIndexArg, sequenceArg, targetSessionId, targetTabId, live);
  };

  // ── 历史分页：服务端还有更早的页 ─────────────────────────────────────
  // 两条事件都属于某个 tab：`history.window` 是本 tab 读回了多少历史，
  // `message.prepended` 是宿主前插的一页。后台 tab 自己吸收，不动当前视图。
  // 已加载页可能一条 user 都没有，所以入口只看 host 的窗口状态，不看 messages。
  const resolveEventTabId = (payload: any, env?: HostEventEnvelope): string => {
    if (env && typeof env.tabId === 'string' && env.tabId) return env.tabId;
    return typeof payload?.tabId === 'string' ? payload.tabId : '';
  };

  const updateHistoryWindow = (payload: any, env?: HostEventEnvelope) => {
    const tabId = resolveEventTabId(payload, env);
    if (!tabId) return;
    const store = getTabStore();
    if (!store.findById(tabId)) return;
    const window = parseHistoryWindow(payload);
    if (!window) return;
    store.updateTab(tabId, (t) => {
      t.historyWindow = window;
    });
  };

  const updateMessagesPrepend = (payload: any, env?: HostEventEnvelope) => {
    const messages = Array.isArray(payload?.messages)
      ? (payload.messages as ClaudeMessage[])
      : (Array.isArray(payload) ? (payload as ClaudeMessage[]) : null);
    if (!messages || messages.length === 0) return;
    const tabId = resolveEventTabId(payload, env);
    if (!tabId) return;
    const store = getTabStore();
    if (!store.findById(tabId)) return;
    const pageStart = payload?.pageStart;
    store.mutateTranscript(tabId, (transcript) => {
      const added = transcript.prependPage(
        messages,
        typeof pageStart === 'number' ? pageStart : undefined,
      );
      // The streaming bubble moved down by exactly the inserted messages.
      if (added > 0 && streamingMessageIndexRef.current >= 0) {
        streamingMessageIndexRef.current += added;
      }
    });
  };

  const updateStatus = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const text = payload?.status as string;
    // The host names the owning tab: routing by tab id is exact.
    const targetTabId = env?.tabId ?? null;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    if (targetTabId && hasTabs) {
      if (!tabStore.isActiveTab(targetTabId)) {
        tabStore.updateTab(targetTabId, (t) => ({ ...t, status: text }));
        return;
      }
    } else if (targetSessionId && hasTabs) {
      const ownerTabId = owningTabIdForSession(targetSessionId);
      if (!ownerTabId) return;
      if (!tabStore.isActiveTab(ownerTabId)) {
        tabStore.updateTab(ownerTabId, (t) => ({ ...t, status: text }));
        return;
      }
    }

    // Resolve localized message if key or known legacy string is passed
    let message = text;
    if (typeof text === 'string') {
      if (text === 'New session created, you can start asking questions' || text === 'toast.newSessionCreatedReady') {
        message = i18n.t('toast.newSessionCreatedReady');
      } else if (text.startsWith('toast.') && i18n.exists(text)) {
        message = i18n.t(text);
      }
    }
    // Do not release the transition guard from generic status updates.
    setStatus(message);
    if (suppressNextStatusToastRef.current) {
      suppressNextStatusToastRef.current = false;
      return;
    }
    // Turn errors are rendered directly in the message transcript and StatusNoticeBar banner;
    // suppress redundant toast notifications.
    if (typeof text === 'string' && text.startsWith('Error:')) {
      return;
    }
    addToast(message);
  };

  const showLoading = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const value = payload?.loading;
    const targetSessionId = resolveSessionId(env?.sessionId ?? null, 'showLoading');
    const isLoading = isTruthy(value);
    if (targetSessionId === null) return;
    // The host names the owning tab: routing by tab id is exact.
    const targetTabId = env?.tabId ?? null;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    if (targetTabId && hasTabs) {
      if (!tabStore.isActiveTab(targetTabId)) {
        tabStore.updateTab(targetTabId, (t) => ({ ...t, loading: isLoading }));
        return;
      }
    } else if (targetSessionId && hasTabs) {
      const ownerTabId = owningTabIdForSession(targetSessionId);
      if (!ownerTabId) return;
      if (!tabStore.isActiveTab(ownerTabId)) {
        tabStore.updateTab(ownerTabId, (t) => ({ ...t, loading: isLoading }));
        return;
      }
    }

    // No "ignore loading=false while streaming" guard here any more.
    //
    // It existed because the turn terminal was unreliable, so an early clear had
    // to be blocked. The daemon now emits an explicit, per-session, idempotent
    // [TURN_END], so a loading=false that arrives here is authoritative: it is
    // this session's own writer saying the turn is over. Deferring it to an
    // onStreamEnd that may never arrive is what left tabs spinning forever.
    //
    // Notify backend about loading state change for tab indicator
    sendWindowCommand('tab.loading.changed', { loading: isLoading });

    // Read the owning tab's current turn state instead of nesting setters
    // inside a functional updater. The tab store applies writes synchronously,
    // so the value read here is the one this event is transitioning away from.
    const activeTab = tabStore.getActiveTab();
    const wasLoading = activeTab?.loading ?? false;
    const turnStartedAt = activeTab?.turnStartedAt ?? null;

    if (isLoading) {
      if (!wasLoading) {
        setLoadingStartTime(Date.now());
      }
    } else if (turnStartedAt != null) {
      // Stamp durationMs on the last assistant message when loading ends.
      // Skip if onStreamEnd already stamped it (avoids double-write race).
      const durationMs = Date.now() - turnStartedAt;
      getTabStore().mutateActiveTranscript((transcript) => {
        transcript.patchLastAssistant((message) => (
          typeof message.durationMs === 'number' ? message : { ...message, durationMs }
        ));
      });
      setLoadingStartTime(null);
    }

    setLoading(isLoading);
  };

  const showThinkingStatus = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const isThinkingVal = isTruthy(payload?.thinking);
    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    const ownerTabId = owningTabIdForSession(targetSessionId);
    if (hasTabs && !ownerTabId) return;
    if (ownerTabId && !tabStore.isActiveTab(ownerTabId)) {
      tabStore.updateTab(ownerTabId, (t) => ({ ...t, isThinking: isThinkingVal }));
      return;
    }
    setIsThinking(isThinkingVal);
  };

  const showSummary = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const summary = String(payload?.summary ?? '');
    if (!summary || !summary.trim()) return;
    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    const ownerTabId = owningTabIdForSession(targetSessionId);
    if (hasTabs && !ownerTabId) return;
    if (ownerTabId && !tabStore.isActiveTab(ownerTabId)) {
      tabStore.updateTab(ownerTabId, (t) => ({ ...t, status: summary }));
      return;
    }
    setStatus(summary);
  };
  const handleHistoryData = (data: any) => {
    if (typeof data === 'string') {
      try {
        setHistoryData(JSON.parse(data));
      } catch {
        setHistoryData({ success: false, error: 'Failed to parse history data' });
      }
    } else {
      setHistoryData(data);
    }
  };

  // 宿主多会话约定：(sessionId, content, uuid)。归属先解析：不属于当前活跃
  // 会话的补丁写进对应的后台 tab，绝不落到前台列表——否则又回到「拿归属当投递」
  // 的老问题。签名曾与宿主错位（宿主发 3 参、这里只收 2 参），导致 content 槽位
  // 收到的是 sessionId，整个补丁静默失效。
  const patchMessageUuid = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId = resolveSessionId(env?.sessionId ?? null, 'patchMessageUuid');
    const content = payload?.content as string;
    const uuid = payload?.uuid as string;
    if (!targetSessionId || !content || !uuid) return;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    const ownerTabId = owningTabIdForSession(targetSessionId);
    if (hasTabs && !ownerTabId) return;
    if (ownerTabId && !tabStore.isActiveTab(ownerTabId)) {
      tabStore.mutateTranscript(ownerTabId, (transcript) => transcript.assignProviderId(content, uuid));
      return;
    }

    if (isSessionTransitioning(null)) return;
    tabStore.mutateActiveTranscript((transcript) => transcript.assignProviderId(content, uuid));
  };

  const clearMessages = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const barrierSequenceArg = payload?.sequence as string | number | undefined;

    const tabStore = getTabStore();
    const ownerTabId = owningTabIdForSession(targetSessionId);
    if (targetSessionId && tabStore.size() > 0 && (!ownerTabId || !tabStore.isActiveTab(ownerTabId))) {
      if (!ownerTabId) return;
      tabStore.mutateTranscript(ownerTabId, (transcript) => transcript.clear());
      tabStore.updateTab(ownerTabId, (t) => {
        t.loading = false;
        t.streamingActive = false;
        // 转录已清空：更早的页属于上一个会话，按钮不能再指向它。
        t.historyWindow = null;
      });
      return;
    }

    cardDebugLog('[clearMessages] called, transitioning:', isSessionTransitioning(targetSessionId), 'barrierSeq:', barrierSequenceArg);
    cardDebugLog('[clearMessages] stack:', new Error().stack?.split('\n').slice(1, 4).join(' | '));
    // Advance the sequence barrier so any updateMessages still in flight from
    // the previous session are rejected. Such a snapshot may already have been
    // dispatched to JS and be sitting in the JCEF IPC channel, so neither the
    // backend's post-reset sequence check nor the tab transition guard
    // time-window guard can stop it once that guard is released. The barrier is
    // the backend coalescer's post-reset updateSequence; stale snapshots carry a
    // strictly smaller sequence. Closes the "new session does not clear" race.
    const barrierSequence = parseSequence(barrierSequenceArg);
    if (barrierSequence != null) {
      resetMinAcceptedSequence(targetSessionId, barrierSequence);
    }
    // Cancel any pending deferred updateMessages to prevent stale data from
    // being applied after messages are cleared.
    if (pendingUpdateRaf !== null) {
      clearTimeout(pendingUpdateRaf);
      pendingUpdateRaf = null;
      pendingUpdateJson = null;
      pendingUpdateSequence = null;
      window.__pendingUpdateRaf = null;
      window.__pendingUpdateJson = null;
      window.__pendingUpdateSequence = null;
    }
    window.__deniedToolIds?.clear();
    window.__lastAcceptedMessageCount = undefined;
    window.__pendingHistoryRefreshMessageCount = undefined;
    window.__historySurfaceRefreshEpoch = (window.__historySurfaceRefreshEpoch ?? 0) + 1;
    resetTransientUiState();
    closeContextUsageDialog();
    // 清空的是这个 tab 的转录：它的历史窗口随之作废，否则「从磁盘加载更早」
    // 会继续显示上一个会话的 hasEarlier。
    const activeTabId = tabStore.getActiveTabId();
    if (activeTabId) {
      tabStore.updateTab(activeTabId, (t) => {
        t.historyWindow = null;
      });
    }
    getTabStore().mutateActiveTranscript((transcript) => transcript.clear());
  };

  const addErrorMessage = (message: unknown) => {
    // `message.error` is published as a named event, so the dispatcher hands
    // over the whole `{ message }` payload instead of the legacy string. A
    // toast cannot render an object (React #31), so normalize both shapes.
    const text = typeof message === 'string'
      ? message
      : (message && typeof message === 'object' && typeof (message as { message?: unknown }).message === 'string'
          ? (message as { message: string }).message
          : '');
    if (text) addToast(text, 'error');
  };

  const showContextUsageDialog = (json: string) => {
    try {
      const result = JSON.parse(json);
      const requestId = typeof result.requestId === 'string' ? result.requestId : null;
      const data: ContextUsageData = result.data || result;
      if (result.success === false) {
        if (closeContextUsageDialog(requestId)) {
          addToast(result.error || 'Failed to get context usage', 'error');
        }
        return;
      }
      updateContextUsageData(requestId, data);
    } catch (e) {
      debugError('[ContextUsage] Failed to parse context usage result:', e);
      closeContextUsageDialog();
      addToast('Failed to parse context usage data', 'error');
    }
  };

  const onContextUsageError = (message: string, requestId?: string) => {
    if (closeContextUsageDialog(requestId)) {
      addToast(message, 'error');
    }
  };

  const addHistoryMessage = (message: ClaudeMessage) => {
    if (isSessionTransitioning(null)) return;
    getTabStore().mutateActiveTranscript((transcript) => {
      transcript.appendRemote(message);
    });
  };


  // History load complete callback — triggers Markdown re-rendering
  // Use full shallow copy to ensure all messages trigger re-render regardless of batching timing
  // Also clear stream-ended markers since history messages don't have __turnId
  const refreshLoadedHistoryMessages = () => {
    window.__lastStreamEndedTurnId = undefined;
    window.__lastStreamEndedAt = undefined;
    // FIX: A replayed session may include aborted turns where some function_call
    // entries never received a function_call_output (user pressed STOP, write_stdin
    // was filtered out, etc.). Without this scan, BashToolGroupBlock / EditToolGroupBlock
    // render those tool_use blocks as perpetual loading spinners because parseBashItem
    // treats `result == null` as "still running". onStreamEnd is NEVER fired during
    // history replay, so the equivalent cleanup there does not help here.
    if (!window.__deniedToolIds) {
      window.__deniedToolIds = new Set<string>();
    }
    // Compute orphans inside the updater (needs the latest message list) but keep
    // the __deniedToolIds mutation OUTSIDE it — a setMessages updater must stay
    // pure, and React StrictMode double-invokes it. Mirrors onStreamEnd's pattern.
    const tabStore = getTabStore();
    const current = tabStore.getActiveTab().transcript.getSnapshot();
    const orphanIds = current.length === 0 ? [] : collectUnresolvedToolUseIds(current, 'all');
    // Fresh message objects force every message component to repaint, so the
    // now-denied IDs are picked up even if the list itself did not change.
    tabStore.mutateActiveTranscript((transcript) => transcript.rehydrate());
    for (const id of orphanIds) {
      window.__deniedToolIds.add(id);
    }
  };

  const historyLoadComplete = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const expectedMessageCountArg = payload?.messageCount as string | number | undefined;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0;
    const isForeground = !hasTabs
      || !targetSessionId
      || tabStore.isActiveSession(targetSessionId);

    if (!isForeground && targetSessionId && hasTabs) {
      tabStore.updateBackgroundTab(targetSessionId, (t) => ({
        ...t,
        loading: false,
        streamingActive: false,
      }));
      // If the tab that owns this session is transitioning toward it, the
      // guard is released now that the history has arrived.
      const ownerTabId = owningTabIdForSession(targetSessionId);
      if (ownerTabId && transitionTargetFor(ownerTabId) === targetSessionId) {
        endTransition(ownerTabId);
      }
      // Only the tab that was loading is done. The tab on screen waits for its
      // own history, so a background completion must not hide its indicator.
      tabStore.setSessionHistoryLoading(targetSessionId, false);
      return;
    }

    cardDebugLog('[HistoryLoadComplete] called, transitioning BEFORE release:', isSessionTransitioning(targetSessionId), 'expectedMsgCount:', expectedMessageCountArg);
    // The load belongs to the tab that owns this session (the tab on screen
    // when the host names none). Release that tab's guard and its indicator.
    const settledTabId = (targetSessionId ? owningTabIdForSession(targetSessionId) : null) ?? activeTabId();
    endTransition(settledTabId);
    cardDebugLog('[HistoryLoadComplete] transitioning AFTER release:', isSessionTransitioning(targetSessionId));
    // Clear the tab that owns the session; with no tab strip (or an unbound
    // session) the tab on screen is the only place the indicator can live.
    if (!targetSessionId || !tabStore.setSessionHistoryLoading(targetSessionId, false)) {
      tabStore.setHistoryLoading(settledTabId, false);
    }
    // 同会话软刷新已完成，释放 loadHistorySession 的去抖守卫
    window.__softSessionReloadInFlight = null;
    const pendingToast = window.__pendingSessionTransitionToast;
    if (pendingToast) {
      window.__pendingSessionTransitionToast = undefined;
      addToast(pendingToast.message, pendingToast.type);
    }
    refreshLoadedHistoryMessages();

    const expectedMessageCount = typeof expectedMessageCountArg === 'number'
      ? expectedMessageCountArg
      : typeof expectedMessageCountArg === 'string' && expectedMessageCountArg.trim().length > 0
        ? Number.parseInt(expectedMessageCountArg, 10)
        : Number.NaN;
    if (Number.isSafeInteger(expectedMessageCount) && expectedMessageCount >= 0) {
      const emptyHistoryNeedsNoSnapshot = expectedMessageCount === 0
        && window.__lastAcceptedMessageCount === undefined;
      if (window.__lastAcceptedMessageCount === expectedMessageCount || emptyHistoryNeedsNoSnapshot) {
        if (emptyHistoryNeedsNoSnapshot) {
          window.__lastAcceptedMessageCount = 0;
        }
        window.__pendingHistoryRefreshMessageCount = undefined;
        requestNativeHistoryRefresh();
      } else {
        window.__pendingHistoryRefreshMessageCount = expectedMessageCount;
      }
    }
  };

  // Legacy Codex callback — kept as no-op to avoid breaking older Java hosts.
  // Unlike historyLoadComplete, this must NOT release the session transition guard.
  const codexHistoryPageRenderComplete = () => {};

  // =========================================================================
  // Codex History Page (paginated history loading)
  // =========================================================================

  const codexPageBuffer = new Map<string, ClaudeMessage[]>();

  const beginCodexHistoryPage = (json: string) => {
    try {
      const { pageId, sessionId } = JSON.parse(json) as {
        pageId: string;
        sessionId: string;
      };
      if (!pageId || !sessionId) return;
      codexPageBuffer.set(pageId, []);
      // Note: __codexHistoryPageInfo is NOT updated here — it retains the
      // previous page's range so completeCodexHistoryPage can verify contiguity.
    } catch (error) {
      console.error('[Frontend] Failed to parse beginCodexHistoryPage:', error);
    }
  };

  const appendCodexHistoryPageBatch = (pageId: string, json: string) => {
    try {
      const messages = JSON.parse(json) as ClaudeMessage[];
      if (!Array.isArray(messages)) return;
      const existing = codexPageBuffer.get(pageId);
      if (existing) {
        existing.push(...messages);
      } else {
        codexPageBuffer.set(pageId, [...messages]);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse appendCodexHistoryPageBatch:', error);
    }
  };

  const completeCodexHistoryPage = (json: string) => {
    try {
      const {
        pageId,
        sessionId,
        mode,
        fromTurn,
        toTurn,
        totalTurns,
        hasMore,
        loadedMessageCount,
      } = JSON.parse(json) as {
        pageId: string;
        sessionId: string;
        mode: 'prepend' | 'replace';
        fromTurn: number;
        toTurn: number;
        totalTurns: number;
        hasMore: boolean;
        loadedMessageCount: number;
      };

      const buffered = codexPageBuffer.get(pageId);
      codexPageBuffer.delete(pageId);
      if (!buffered) return;

      // Ignore if the session changed during the transfer. The tab strip knows
      // which session is on screen; the ref is only the fallback.
      const tabStore = getTabStore();
      const stillCurrent = tabStore.size() > 0
        ? tabStore.isActiveSession(sessionId)
        : currentSessionIdRef.current === sessionId; // legacy-allow: fallback when no tab strip exists
      if (!stillCurrent) return;

      // Contiguity check: for prepend mode, the new page's toTurn must match
      // the existing page's fromTurn. If there's a gap, reject and show error.
      const existingPageInfo = window.__codexHistoryPageInfo;
      if (mode === 'prepend' && existingPageInfo) {
        if (existingPageInfo.fromTurn > 0 && toTurn !== existingPageInfo.fromTurn) {
          addToast('Codex history changed while loading; please retry', 'error');
          return;
        }
      }

      getTabStore().mutateSessionTranscript(sessionId, (transcript) => {
        if (mode === 'replace') {
          transcript.clear();
          transcript.applySnapshot(buffered, 0);
        } else {
          const added = transcript.prependPage(buffered);
          // The streaming bubble moved down by exactly the inserted messages.
          if (added > 0 && streamingMessageIndexRef.current >= 0) {
            streamingMessageIndexRef.current += added;
          }
        }
      });

      // Update page info
      window.__codexHistoryPageInfo = {
        pageId,
        sessionId,
        mode,
        fromTurn,
        toTurn,
        totalTurns,
        hasMore,
        loadedMessageCount,
      };

    } catch (error) {
      console.error('[Frontend] Failed to parse completeCodexHistoryPage:', error);
    }
  };

  const addUserMessage = (content: string) => {
    if (isSessionTransitioning(null)) return;
    const userMessage: ClaudeMessage = {
      type: 'user',
      content: content || '',
      timestamp: new Date().toISOString(),
    };
    getTabStore().mutateActiveTranscript((transcript) => {
      // The optimistic bubble already shows this sentence; the host echo is
      // the same message, not a second one.
      if (transcript.hasLocalMatching(content)) return;
      transcript.appendRemote(userMessage);
    });
    userPausedRef.current = false;
    isUserAtBottomRef.current = true;
    requestAnimationFrame(() => {
      if (messagesContainerRef.current) {
        messagesContainerRef.current.scrollTop = messagesContainerRef.current.scrollHeight;
      }
    });
  };

  const onSessionHydrated = (packetJson: string) => {
    try {
      const packet = JSON.parse(packetJson);
      const {
        sessionId: targetSessionId,
        messages: authoritativeMessages,
        live: liveDraft,
        isStreaming,
        status,
        loading,
        revertBoundaryId,
      } = packet;

      if (!targetSessionId || !Array.isArray(authoritativeMessages)) {
        return;
      }

      const live = liveDraft && typeof liveDraft === 'object' ? liveDraft as ClaudeMessage : null;

      cardDebugLog('[onSessionHydrated] received for session:', targetSessionId, 'count:', authoritativeMessages.length);

      const tabStore = getTabStore();
      // Ownership includes a pending transition: the hydrate for a session that
      // is still being bound belongs to the tab switching to it.
      const ownerTabId = owningTabIdForSession(targetSessionId);
      const isForeground = ownerTabId
        ? tabStore.isActiveTab(ownerTabId)
        : tabStore.size() === 0;

      if (isForeground) {
        tabStore.mutateActiveTranscript((transcript) => {
          transcript.clear();
          transcript.applySnapshot(authoritativeMessages, 0, live);
        });

        if (live) {
          window.__pendingLiveDraftJson = JSON.stringify(live);
        }

        if (typeof isStreaming === 'boolean') {
          isStreamingRef.current = isStreaming;
          setStreamingActive?.(isStreaming);
          if (isStreaming) {
            if (live) {
              streamingContentRef.current = live.content || '';
              streamingMessageIndexRef.current = -1;
            } else {
              const lastAssistantIdx = findLastAssistantIndex(authoritativeMessages);
              if (lastAssistantIdx >= 0) {
                streamingMessageIndexRef.current = lastAssistantIdx;
                const lastAssistant = authoritativeMessages[lastAssistantIdx];
                streamingContentRef.current = lastAssistant?.content || '';
              }
            }
          } else {
            streamingContentRef.current = '';
          }
        }

        if (typeof loading === 'boolean') {
          setLoading(loading);
        }

        if (typeof status === 'string') {
          setStatus(status);
        }

        if (typeof revertBoundaryId !== 'undefined' && window.onRevertStateUpdate) {
          window.onRevertStateUpdate(
            targetSessionId,
            revertBoundaryId ? { hasRevert: true, messageId: revertBoundaryId } : { hasRevert: false },
          );
        }
      }

      // Also ensure background tab store has the authoritative snapshot
      const applyHydrated = (t: any) => {
        t.transcript.clear();
        t.transcript.applySnapshot(authoritativeMessages, 0, live);
        if (typeof isStreaming === 'boolean') t.streamingActive = isStreaming;
        if (typeof loading === 'boolean') t.loading = loading;
        if (typeof status === 'string') t.status = status;
        if (typeof revertBoundaryId !== 'undefined') t.revertBoundaryId = revertBoundaryId;
        return { ...t };
      };
      if (ownerTabId) {
        tabStore.updateTab(ownerTabId, applyHydrated);
      }
    } catch (e) {
      console.error('[MultiSession] Failed to parse onSessionHydrated', e);
    }
  };


  // The semantic catalog is the routing table.
  registerHostEvents({
    'message.history': addHistoryMessage,
    'message.added': addUserMessage,
    'session.cleared': clearMessages,
    'session.loaded': historyLoadComplete,
    'message.uuid.patched': patchMessageUuid,
    'session.loading': showLoading,
    'session.summary': showSummary,
    'thinking.status': showThinkingStatus,
    'message.tail': updateMessageTail,
    'message.snapshot': updateMessages,
    'message.prepended': updateMessagesPrepend,
    'history.window': updateHistoryWindow,
    'status.updated': updateStatus,
    'message.error': addErrorMessage,
    'context.usage.dialog': showContextUsageDialog,
    'context.usage.error': onContextUsageError,
    'history.data': handleHistoryData,
    'session.hydrated': onSessionHydrated,
    'internal.history.codexBegin': beginCodexHistoryPage,
    'internal.history.codexAppend': appendCodexHistoryPageBatch,
    'internal.history.codexComplete': completeCodexHistoryPage,
    'internal.history.codexRenderComplete': codexHistoryPageRenderComplete,
  });
}
