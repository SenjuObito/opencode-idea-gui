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
import { isSessionTransitioning, transitionTargetOfSession } from '../tabTransition';
import type { HostEventEnvelope, HostEventPayload } from '../../../utils/hostEventTypes';
import { debugError } from '../../../utils/debug';
import { resolveSessionId } from '../../../utils/sessionIdentity';
import {
  appendOptimisticMessageIfMissing,
  ensureStreamingAssistantInList,
  getRawUuid,
  isUserMessageContentMatch,
  preserveLastAssistantIdentity,
  preserveLatestMessagesOnShrink,
  preserveStreamingAssistantContent,
  stripDuplicateTrailingToolMessages,
} from '../messageSync';
import { activeTabId, endTransition } from '../tabTransition';
import {
  parseSequence,
  getMinAcceptedSequence,
  advanceMinAcceptedSequence,
  resetMinAcceptedSequence,
} from '../sequenceBarrier';
import { reconstructTurnMetadata } from '../../../utils/turnMetadataReconstruction';
import { collectUnresolvedToolUseIds } from './streamingCallbacks';
import i18n from '../../../i18n/config';
import type { TabMessageStore } from '../../../store/tabMessageStore';
import { getTabStore } from '../../../store/tabStore';

const isTruthy = (v: unknown) => v === true || v === 'true';


export function registerMessageCallbacks(
  options: UseWindowCallbacksOptions,
  resetTransientUiState: () => void,
  requestHistoryRenderCommit: (refreshEpoch: number) => void,
): void {
  const {
    addToast,
    setMessages,
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
    isStreamingRef,
    useBackendStreamingRenderRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    findLastAssistantIndex,
    extractRawBlocks,
    patchAssistantForStreaming,
    updateContextUsageData,
    closeContextUsageDialog,
    currentSessionIdRef,
    setStreamingActive,
  } = options;

  const ensureStreamingAssistantPreserved = (prevList: ClaudeMessage[], resultList: ClaudeMessage[]): ClaudeMessage[] => {
    const { list, streamingIndex } = ensureStreamingAssistantInList(
      prevList,
      resultList,
      isStreamingRef.current,
      streamingTurnIdRef.current,
    );
    if (streamingIndex >= 0) {
      streamingMessageIndexRef.current = streamingIndex;
    }
    return list;
  };

  const finalizeMessageList = (prevList: ClaudeMessage[], resultList: ClaudeMessage[]): ClaudeMessage[] => {
    const withoutDuplicateToolTail = stripDuplicateTrailingToolMessages(
      resultList,
      options.currentProviderRef.current,
    );
    return ensureStreamingAssistantPreserved(prevList, withoutDuplicateToolTail);
  };

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
  const getPrependedHistoryMessageCount = (messageCount: number): number => {
    const count = window.__prependedHistoryMessageCount;
    return typeof count === 'number'
      && Number.isSafeInteger(count)
      && count >= 0
      && count <= messageCount
      ? count
      : 0;
  };

  const sessionBaseIndexMap = new Map<string, number>();

  const getBaseIndexForSession = (sessionId: string | null): number => {
    if (sessionId && sessionBaseIndexMap.has(sessionId)) {
      return sessionBaseIndexMap.get(sessionId)!;
    }
    const tabStore = getTabStore();
    const tab = sessionId ? (tabStore.findTabBySessionId(sessionId) ?? window.__sessionTabsStore?.getTabs?.().find((t) => t.sessionId === sessionId)) : null;
    if (tab && typeof tab.messageBaseIndex === 'number') {
      return tab.messageBaseIndex;
    }
    return typeof window.__messageBaseIndex === 'number' ? window.__messageBaseIndex : 0;
  };

  const setBaseIndexForSession = (sessionId: string | null, baseIndex: number): void => {
    if (sessionId) {
      sessionBaseIndexMap.set(sessionId, baseIndex);
    }
    const tabStore = getTabStore();
    const tab = sessionId ? (tabStore.findTabBySessionId(sessionId) ?? window.__sessionTabsStore?.getTabs?.().find((t) => t.sessionId === sessionId)) : null;
    if (tab) {
      tab.messageBaseIndex = baseIndex;
    }
    const activeSessionId = tabStore.getActiveSessionId() || window.__sessionTabsStore?.getActiveSessionId?.() || options.currentSessionIdRef?.current;
    if (!sessionId || !activeSessionId || sessionId === activeSessionId) {
      window.__messageBaseIndex = baseIndex;
    }
  };

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

  const processUpdateMessages = (
    json: string,
    sequence: number | null = null,
    baseIndexArg?: string | number | null,
    targetSessionId?: string | null,
  ) => {
    cardDebugLog('[processUpdateMessages] called, seq:', sequence, 'isStreaming:', isStreamingRef.current, 'jsonLen:', json.length, 'transitioning:', isSessionTransitioning(targetSessionId ?? null));
    // Re-check the session-transition guard inside processUpdateMessages so the
    // rAF-deferred path (window.updateMessages → setTimeout → processUpdateMessages)
    // cannot resurrect cleared messages when a transition starts between the
    // entry-point check and the deferred fire. This also catches synchronous
    // callers that bypass the entry point — addHistoryMessage / addUserMessage
    // already guard, but processUpdateMessages is the canonical setter and
    // should be self-defending.
    if (isSessionTransitioning(targetSessionId)) {
      // The tab is switching sessions: only the session it is switching *to*
      // may write. Events of the outgoing session are dropped.
      const transitionTarget = transitionTargetOfSession(targetSessionId);
      const isTargetActiveSession = Boolean(targetSessionId && transitionTarget && targetSessionId === transitionTarget);
      if (!isTargetActiveSession) {
        cardDebugLog('[processUpdateMessages] BLOCKED: tab is transitioning');
        return;
      }
      cardDebugLog('[processUpdateMessages] ALLOWED during transition for targetSessionId:', targetSessionId);
    }
    // A frame deferred 16ms during streaming can outlive a tab switch. If the
    // session is no longer the one on screen, applying it here would write one
    // conversation into another tab's transcript (and retrigger auto-scroll).
    const tabStore = getTabStore();
    const isBackground = targetSessionId && (
      (tabStore.size() > 0 && !tabStore.isActiveSession(targetSessionId)) ||
      (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId))
    );
    if (isBackground) {
      cardDebugLog('[processUpdateMessages] DROPPED: target is not the active session', targetSessionId);
      return;
    }
    const effectiveSessionId = targetSessionId ?? null;
    const minAcceptedSequence = getMinAcceptedSequence(effectiveSessionId);
    if (sequence != null && sequence < minAcceptedSequence) {
      cardDebugLog('[processUpdateMessages] BLOCKED: seq', sequence, '< minAccepted', minAcceptedSequence, 'sessionId:', effectiveSessionId);
      return;
    }

    try {
      // Reconstruct per-turn footer metadata (durationMs / turnUsage) for
      // settled turns in the snapshot — the persisted transcript carries
      // neither field, so reloaded sessions lost the duration/token footer.
      // The trailing turn is skipped while streaming: live stamping owns it.
      const raw = JSON.parse(json) as unknown;
      if (!Array.isArray(raw)) return;
      const backendMessages = raw as ClaudeMessage[];
      if (sequence != null) {
        advanceMinAcceptedSequence(effectiveSessionId, sequence);
      }

      // ── 窗口语义（opencode 活跃会话滑动窗口）──
      // 第三个参数是快照对应的宿主窗口基址（全局序号）。基址变化意味着宿主
      // 窗口滑动（头部逐出）：此时做「窗口精确拼接」——保留 webview 列表中
      // 全局序号位于新基址之前的内容（分页前插的更早页 + 旧窗口头部），
      // 用新快照替换窗口部分。禁用 smart-merge / 收缩保护：索引已整体位移，
      // 按下标对齐的合并是错的。
      const baseIndex = baseIndexArg != null && baseIndexArg !== ''
        ? Number(baseIndexArg)
        : null;
      const hasWindowBase = baseIndex != null && Number.isSafeInteger(baseIndex) && baseIndex >= 0;
      const prevWindowBase = getBaseIndexForSession(effectiveSessionId);
      const windowChanged = hasWindowBase && (baseIndex as number) !== prevWindowBase;
      if (windowChanged) {
        const newBase = baseIndex as number;
        setBaseIndexForSession(effectiveSessionId, newBase);
        setMessages((prev) => {
          const listStart = window.__opencodeListStart ?? 0;
          // 保留 prev 中全局序号 < newBase 的头部（分页内容 + 旧窗口头部），
          // 弥合窗口滑动造成的空档，保证全局连续。
          const keepCount = Math.max(0, Math.min(newBase - listStart, prev.length));
          const merged = keepCount > 0
            ? [...prev.slice(0, keepCount), ...backendMessages]
            : backendMessages;
          const result = reconstructTurnMetadata(
            merged,
            { skipTrailingTurn: isStreamingRef.current },
          );
          return finalizeMessageList(prev, result);
        });
        window.__lastAcceptedMessageCount = backendMessages.length;
        refreshRestoredHistoryIfPending(backendMessages.length);
        return;
      }

      setBaseIndexForSession(effectiveSessionId, hasWindowBase ? (baseIndex as number) : 0);

      setMessages((prev) => {
        const prependedCount = getPrependedHistoryMessageCount(prev.length);
        const parsed = reconstructTurnMetadata(
          prependedCount > 0
            ? [...prev.slice(0, prependedCount), ...backendMessages]
            : backendMessages,
          { skipTrailingTurn: isStreamingRef.current },
        );
        // If streaming is active, delegate to the streaming logic
        if (isStreamingRef.current) {
          if (useBackendStreamingRenderRef.current) {
            let smartMerged = parsed.map((newMsg, i) => {
              if (i === parsed.length - 1) return newMsg;
              if (i < prev.length) {
                const oldMsg = prev[i];
                // Preserve frontend-only durationMs across backend updates
                if (typeof oldMsg.durationMs === 'number' && newMsg.type === 'assistant') {
                  newMsg = { ...newMsg, durationMs: oldMsg.durationMs };
                }
                if (oldMsg.type === 'user' && newMsg.type === 'user' && isUserMessageContentMatch(oldMsg.content || '', newMsg.content || '')) {
                  newMsg = { ...newMsg, content: oldMsg.content };
                }
                if (
                  oldMsg.timestamp === newMsg.timestamp &&
                  oldMsg.type === newMsg.type &&
                  oldMsg.content === newMsg.content
                ) {
                  return oldMsg;
                }
              }
              return newMsg;
            });

            smartMerged = preserveLastAssistantIdentity(prev, smartMerged, findLastAssistantIndex);
            smartMerged = preserveStreamingAssistantContent(
              prev,
              smartMerged,
              isStreamingRef,
              streamingContentRef,
              findLastAssistantIndex,
              patchAssistantForStreaming,
            );
            const result = preserveLatestMessagesOnShrink(
              prev,
              appendOptimisticMessageIfMissing(prev, smartMerged),
              options.currentProviderRef.current,
            );

            // FIX: In Claude mode, update streamingMessageIndexRef so that
            // onContentDelta knows which assistant message to update.
            let lastAssistantIdx = findLastAssistantIndex(result);
            // Verify the found assistant belongs to the current streaming turn
            if (lastAssistantIdx >= 0 && streamingTurnIdRef.current > 0 &&
                result[lastAssistantIdx].__turnId !== streamingTurnIdRef.current) {
              // Scan for the correct turn ID match (from end, consistent with findLastAssistantIndex)
              for (let i = result.length - 1; i >= 0; i--) {
                if (result[i].type === 'assistant' && result[i].__turnId === streamingTurnIdRef.current) {
                  lastAssistantIdx = i;
                  break;
                }
              }
            }
            if (lastAssistantIdx >= 0) {
              streamingMessageIndexRef.current = lastAssistantIdx;

              // Always stamp __turnId so ensureStreamingAssistantPreserved can find it,
              // even before any content delta arrives (streamingContentRef may be empty).
              if (result[lastAssistantIdx]?.__turnId !== streamingTurnIdRef.current) {
                result[lastAssistantIdx] = {
                  ...result[lastAssistantIdx],
                  __turnId: streamingTurnIdRef.current,
                };
              }

              // FIX: If there is buffered streaming content (onContentDelta may
              // fire before updateMessages), apply it to the assistant message
              // immediately to prevent content loss.
              if (streamingContentRef.current && result[lastAssistantIdx]?.type === 'assistant') {
                const backendContent = result[lastAssistantIdx].content || '';
                if (streamingContentRef.current.length >= backendContent.length) {
                  result[lastAssistantIdx] = patchAssistantForStreaming({
                    ...result[lastAssistantIdx],
                    content: streamingContentRef.current,
                    isStreaming: true,
                  });
                } else {
                  // Backend has more complete content; sync buffer
                  streamingContentRef.current = backendContent;
                }
              }
            }

            return finalizeMessageList(prev, result);
          }

          const lastAssistantIdx = findLastAssistantIndex(parsed);
          if (lastAssistantIdx < 0) {
            return finalizeMessageList(
              prev,
              preserveLatestMessagesOnShrink(
                prev,
                appendOptimisticMessageIfMissing(prev, parsed),
                options.currentProviderRef.current,
              ),
            );
          }
        }

        // Non-streaming case (or streaming hasn't started yet)
        if (!isStreamingRef.current) {
          // Smart merge: reuse old message objects for performance
          let smartMerged = parsed.map((newMsg, i) => {
            if (i < prev.length) {
              const oldMsg = prev[i];
              // Preserve frontend-only durationMs across backend updates
              if (typeof oldMsg.durationMs === 'number' && newMsg.type === 'assistant') {
                newMsg = { ...newMsg, durationMs: oldMsg.durationMs };
              }
              if (oldMsg.type === 'user' && newMsg.type === 'user' && isUserMessageContentMatch(oldMsg.content || '', newMsg.content || '')) {
                newMsg = { ...newMsg, content: oldMsg.content };
              }
              if (i < parsed.length - 1) {
                if (
                  oldMsg.timestamp === newMsg.timestamp &&
                  oldMsg.type === newMsg.type &&
                  oldMsg.content === newMsg.content
                ) {
                  return oldMsg;
                }
              }
            }
            return newMsg;
          });

          smartMerged = preserveLastAssistantIdentity(prev, smartMerged, findLastAssistantIndex);
          smartMerged = preserveLatestMessagesOnShrink(prev, smartMerged, options.currentProviderRef.current);
          return finalizeMessageList(prev, appendOptimisticMessageIfMissing(prev, smartMerged));
        }

        // Streaming + !useBackendStreamingRender: always accept the backend snapshot
        // but preserve the streaming assistant's text content (which arrives via
        // onContentDelta and is more up-to-date than the coalesced snapshot).
        //
        // Previously this branch only accepted snapshots containing tool_use blocks,
        // which caused ALL updateMessages to be silently dropped during pure-text
        // streaming.  When onStreamEnd was subsequently lost (JCEF async chain),
        // the UI appeared permanently frozen.

        let patched = [...parsed];
        patched = appendOptimisticMessageIfMissing(prev, patched);
        patched = preserveLastAssistantIdentity(prev, patched, findLastAssistantIndex);
        patched = preserveStreamingAssistantContent(
          prev,
          patched,
          isStreamingRef,
          streamingContentRef,
          findLastAssistantIndex,
          patchAssistantForStreaming,
        );
        patched = preserveLatestMessagesOnShrink(prev, patched, options.currentProviderRef.current);

        const patchedAssistantIdx = findLastAssistantIndex(patched);
        if (patchedAssistantIdx >= 0 && patched[patchedAssistantIdx]?.type === 'assistant') {
          const patchedAssistant = patched[patchedAssistantIdx];
          const currentTurnId = streamingTurnIdRef.current;
          // Stale: backend snapshot hasn't caught up — findLastAssistantIndex returns prior turn's assistant
          const isStaleSnapshot = currentTurnId > 0 && patchedAssistant.__turnId !== undefined
            && patchedAssistant.__turnId !== currentTurnId;
          if (!isStaleSnapshot) {
            streamingMessageIndexRef.current = patchedAssistantIdx;
            patched[patchedAssistantIdx] = patchAssistantForStreaming({
              ...patchedAssistant,
              __turnId: currentTurnId,
            });
          }
        }

        // Always accept the backend snapshot during streaming. The coalescer
        // (150ms minimum interval) and rAF batching (16ms) already throttle
        // updates. Skipping snapshots via a structural-change gate caused
        // StatusPanel tasks/edits to freeze during active streaming because
        // the gate suppressed message state updates, preventing downstream
        // recomputation of statusScopeMessages, useFileChanges, and
        // deriveTodosForTurn.
        return finalizeMessageList(prev, patched);
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
  ) => {
    if (isSessionTransitioning(targetSessionId)) {
      const transitionTarget = transitionTargetOfSession(targetSessionId);
      const isTargetActiveSession = Boolean(targetSessionId && transitionTarget && targetSessionId === transitionTarget);
      if (!isTargetActiveSession) return;
    }
    // Same deferred-frame guard as processUpdateMessages: a tail pushed for a
    // background session must never be applied after the user switched away.
    const tabStore = getTabStore();
    const isBackground = targetSessionId && (
      (tabStore.size() > 0 && !tabStore.isActiveSession(targetSessionId)) ||
      (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId))
    );
    if (isBackground) {
      cardDebugLog('[processMessageTail] DROPPED: target is not the active session', targetSessionId);
      return;
    }
    const effectiveSessionId = targetSessionId ?? null;
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

      setMessages((prev) => {
        const prependedCount = getPrependedHistoryMessageCount(prev.length);
        const prependedHistory = prependedCount > 0
          ? prev.slice(0, prependedCount)
          : [];
        const storedBaseIndex = getBaseIndexForSession(effectiveSessionId);
        const currentBaseIndex = typeof storedBaseIndex === 'number' && Number.isSafeInteger(storedBaseIndex)
          ? Math.max(0, storedBaseIndex)
          : 0;
        const backendMessageCount = prev.length - prependedCount;
        const hasFullPrefix = currentBaseIndex === 0 && baseIndex <= backendMessageCount;
        let merged = hasFullPrefix
          ? [...prev.slice(0, prependedCount + baseIndex), ...tail]
          : [...prependedHistory, ...tail];
        setBaseIndexForSession(effectiveSessionId, hasFullPrefix ? 0 : baseIndex);

        merged = appendOptimisticMessageIfMissing(prev, merged);
        merged = preserveLastAssistantIdentity(prev, merged, findLastAssistantIndex);
        merged = preserveStreamingAssistantContent(
          prev,
          merged,
          isStreamingRef,
          streamingContentRef,
          findLastAssistantIndex,
          patchAssistantForStreaming,
        );

        if (isStreamingRef.current) {
          const assistantIndex = findLastAssistantIndex(merged);
          if (assistantIndex >= 0 && merged[assistantIndex]?.type === 'assistant') {
            streamingMessageIndexRef.current = assistantIndex;
            const currentTurnId = streamingTurnIdRef.current;
            if (currentTurnId > 0 && merged[assistantIndex].__turnId !== currentTurnId) {
              merged[assistantIndex] = patchAssistantForStreaming({
                ...merged[assistantIndex],
                __turnId: currentTurnId,
              });
            }
          }
        }

        merged = reconstructTurnMetadata(
          merged,
          { skipTrailingTurn: isStreamingRef.current },
        );
        if (merged.length === 0 && prev.length > 0 && isStreamingRef.current) {
          return prev;
        }
        return finalizeMessageList(prev, merged);
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

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0 || Boolean(window.__sessionTabsStore);
    const isForegroundSession = !hasTabs
      || !targetSessionId
      || (targetSessionId === options.currentSessionIdRef?.current)
      || (tabStore.size() > 0 ? tabStore.isActiveSession(targetSessionId) : false)
      || (Boolean(window.__sessionTabsStore?.isActiveSession(targetSessionId)));

    if (!isForegroundSession && targetSessionId && hasTabs) {
      const applyToBackgroundTab = (t: any) => {
        try {
          const raw = JSON.parse(json);
          if (Array.isArray(raw)) {
            // The tab owns its transcript: the snapshot is reconciled by
            // message id, so a message keeps its identity across snapshots.
            const store = t.messageStore as TabMessageStore | undefined;
            const nextMessages = store ? store.applySnapshot(raw) : raw;
            t.messages = nextMessages;
            return { ...t, messages: nextMessages };
          }
        } catch (e) {
          console.error('[MultiSession] Failed to parse background updateMessages', e);
        }
        return t;
      };

      if (targetTabId) {
        // The tab id is known: write to exactly that tab. No lookup, no
        // guessing, and a closed tab simply has nowhere to write.
        let written = false;
        if (tabStore.size() > 0) {
          written = tabStore.updateTab(targetTabId, applyToBackgroundTab as any);
        }
        if (!written && window.__sessionTabsStore?.updateTab) {
          written = Boolean(window.__sessionTabsStore.updateTab(targetTabId, applyToBackgroundTab));
        }
        if (!written) {
          cardDebugLog('[updateMessages] dropped: no tab', targetTabId, 'owns this snapshot');
        }
        return;
      }

      let written = false;
      if (tabStore.size() > 0) {
        written = tabStore.updateBackgroundTab(targetSessionId, applyToBackgroundTab as any);
      }
      if (!written && window.__sessionTabsStore?.updateBackgroundTab) {
        window.__sessionTabsStore.updateBackgroundTab(
          (t) => t.sessionId === targetSessionId,
          applyToBackgroundTab
        );
      }
      // No tab claims this session: the user closed it (or the host pushed an
      // id the webview never bound). Drop the snapshot — falling through to the
      // active path would render another conversation's messages in the tab the
      // user is currently looking at.
      return;
    }

    // During session transition, ignore message updates from stale session
    // callbacks to prevent cleared messages from being restored
    cardDebugLog('[updateMessages] called, transitioning:', isSessionTransitioning(targetSessionId), 'seq:', sequenceArg, 'jsonLen:', json?.length);
    if (isSessionTransitioning(targetSessionId)) {
      const transitionTarget = transitionTargetOfSession(targetSessionId);
      const isTargetActiveSession = Boolean(targetSessionId && transitionTarget && targetSessionId === transitionTarget);
      if (!isTargetActiveSession) {
        cardDebugLog('[updateMessages] BLOCKED: tab is transitioning');
        return;
      }
      cardDebugLog('[updateMessages] ALLOWED during transition for targetSessionId:', targetSessionId);
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
      window.__pendingUpdateJson = json;
      window.__pendingUpdateSequence = sequence;
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
          pendingUpdateJson = null;
          pendingUpdateSequence = null;
          pendingUpdateBaseIndex = null;
          window.__pendingUpdateJson = null;
          window.__pendingUpdateSequence = null;
          // A session transition may have begun while this frame was buffered.
          // processUpdateMessages re-checks the transition guard itself now, so
          // this early return is defense-in-depth — without either check, a
          // stale snapshot deferred during the outgoing session's streaming
          // would run down the non-streaming path (isStreamingRef was cleared
          // by beginSessionTransition) and resurrect the cleared messages.
          if (isSessionTransitioning(targetSessionId)) {
            const transitionTarget = transitionTargetOfSession(targetSessionId);
            const isTargetActiveSession = Boolean(targetSessionId && transitionTarget && targetSessionId === transitionTarget);
            if (!isTargetActiveSession) return;
          }
          if (latestJson) {
            processUpdateMessages(latestJson, latestSequence, latestBaseIndex, targetSessionId);
          }
        }, 16);
        pendingUpdateRaf = timerId as unknown as number;
        window.__pendingUpdateRaf = timerId as unknown as number;
      }
      return;
    }

    processUpdateMessages(json, sequence, baseIndexArg, targetSessionId);
  };

  const flushPendingUpdateMessages = (targetSessionId?: string | null) => {
    const json = pendingUpdateJson ?? window.__pendingUpdateJson ?? null;
    const sequence = pendingUpdateSequence ?? null;
    const baseIndex = pendingUpdateBaseIndex;
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
    const json = String(payload?.messages ?? '[]');
    const baseIndexArg = payload?.baseIndex as string | number;
    const sequenceArg = payload?.sequence as string | number | undefined;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0 || Boolean(window.__sessionTabsStore);
    const isBackground = hasTabs && targetSessionId && (
      (tabStore.size() > 0 && !tabStore.isActiveSession(targetSessionId)) ||
      (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId))
    );
    if (isBackground && targetSessionId) {
      const applyTail = (t: any) => {
        try {
          const tail = JSON.parse(json);
          if (Array.isArray(tail)) {
            const base = typeof baseIndexArg === 'number' ? baseIndexArg : Number(baseIndexArg);
            const currentMessages = t.messages || [];
            let nextMessages = tail;
            if (Number.isSafeInteger(base) && base >= 0 && base <= currentMessages.length) {
              nextMessages = [...currentMessages.slice(0, base), ...tail];
            } else if (currentMessages.length > 0) {
              nextMessages = [...currentMessages, ...tail];
            }
            const nextBase = Number.isSafeInteger(base) ? base : 0;
            t.messages = nextMessages;
            t.messageBaseIndex = nextBase;
            return { ...t, messages: nextMessages, messageBaseIndex: nextBase };
          }
        } catch (e) {
          console.error('[MultiSession] Failed to parse background updateMessageTail', e);
        }
        return t;
      };

      let written = false;
      if (tabStore.size() > 0) {
        written = tabStore.updateBackgroundTab(targetSessionId, applyTail as any);
      }
      if (!written && window.__sessionTabsStore) {
        window.__sessionTabsStore.updateBackgroundTab(
          (t) => t.sessionId === targetSessionId,
          applyTail
        );
      }
      return;
    }

    processMessageTail(json, baseIndexArg, sequenceArg, targetSessionId);
  };

  // ── opencode 恢复分页/回源：前插更早的历史页 ──
  // 宿主窗口（恢复或活跃会话逐出）之外的历史经 `load_earlier_messages` 回源，
  // 宿主回推本回调前插。第二个参数是页起点全局序号：webview 用它维护
  // __opencodeListStart（列表首条的全局序号），后续窗口快照据此拼接。
  // __prependedHistoryMessageCount 同步累加：基址不变的同代快照走 legacy
  // 合并路径时，靠它保留已前插的页（Codex 既有机制）。
  const updateMessagesPrepend = (json: string, pageStartArg?: number | string) => {
    if (isSessionTransitioning(null)) return;
    if (isStreamingRef.current) return;
    let parsed: ClaudeMessage[];
    try {
      parsed = JSON.parse(json) as ClaudeMessage[];
    } catch (error) {
      console.error('[Frontend] Failed to parse earlier messages page:', error);
      return;
    }
    if (!Array.isArray(parsed) || parsed.length === 0) return;
    const pageStart = pageStartArg != null && pageStartArg !== ''
      ? Number(pageStartArg)
      : null;
    const container = messagesContainerRef.current;
    const oldScrollHeight = container?.scrollHeight ?? 0;
    const oldScrollTop = container?.scrollTop ?? 0;

    // ── 分页累积上限：页区域超过 MAX_PAGED_MESSAGES 时丢最老的页 ──
    // 大型会话用户连点分页会让 webview state 无界增长；被裁掉的区间只影响
    // 本地窗口，宿主下一次仍按请求里的 pageStart 回源，不产生取不到的空档。
    const MAX_PAGED_MESSAGES = 800;
    let drop = 0;
    let newStart = pageStart != null && Number.isSafeInteger(pageStart) && (pageStart as number) >= 0
      ? pageStart as number
      : (window.__opencodeListStart ?? 0);
    const windowBase = window.__messageBaseIndex ?? 0;
    if (windowBase > newStart) {
      const pagedCount = windowBase - newStart;
      if (pagedCount > MAX_PAGED_MESSAGES) {
        drop = pagedCount - MAX_PAGED_MESSAGES;
        newStart += drop;
      }
    }

    setMessages((prev) => (drop > 0 ? [...parsed, ...prev].slice(drop) : [...parsed, ...prev]));
    window.__opencodeListStart = newStart;
    const prevPrepended = window.__prependedHistoryMessageCount;
    window.__prependedHistoryMessageCount =
      Math.max(0, (typeof prevPrepended === 'number' ? prevPrepended : 0) + parsed.length - drop);
    if (container) {
      requestAnimationFrame(() => {
        const currentContainer = messagesContainerRef.current;
        if (!currentContainer) return;
        currentContainer.scrollTop = oldScrollTop + currentContainer.scrollHeight - oldScrollHeight;
      });
    }
  };

  const updateStatus = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const text = payload?.status as string;
    // The host names the owning tab: routing by tab id is exact.
    const targetTabId = env?.tabId ?? null;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0 || Boolean(window.__sessionTabsStore);
    if (targetTabId && hasTabs) {
      if (!tabStore.isActiveTab(targetTabId)) {
        tabStore.updateTab(targetTabId, (t) => ({ ...t, status: text }));
        window.__sessionTabsStore?.updateTab?.(targetTabId, (t: any) => {
          t.status = text;
        });
        return;
      }
    } else if (targetSessionId && hasTabs) {
      const isBackground = (tabStore.size() > 0 && !tabStore.isActiveSession(targetSessionId)) ||
        (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId));
      if (isBackground) {
        tabStore.updateBackgroundTab(targetSessionId, (t) => ({ ...t, status: text }));
        window.__sessionTabsStore?.updateBackgroundTab?.(
          (t) => t.sessionId === targetSessionId,
          (t) => {
            t.status = text;
          }
        );
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
    const hasTabs = tabStore.size() > 0 || Boolean(window.__sessionTabsStore);
    if (targetTabId && hasTabs) {
      if (!tabStore.isActiveTab(targetTabId)) {
        tabStore.updateTab(targetTabId, (t) => ({ ...t, loading: isLoading }));
        window.__sessionTabsStore?.updateTab?.(targetTabId, (t: any) => {
          t.loading = isLoading;
        });
        return;
      }
    } else if (targetSessionId && hasTabs) {
      const isBackground = (tabStore.size() > 0 && !tabStore.isActiveSession(targetSessionId)) ||
        (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId));
      if (isBackground) {
        tabStore.updateBackgroundTab(targetSessionId, (t) => ({ ...t, loading: isLoading }));
        window.__sessionTabsStore?.updateBackgroundTab?.(
          (t) => t.sessionId === targetSessionId,
          (t) => {
            t.loading = isLoading;
          }
        );
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
      setMessages((prev) => {
        for (let i = prev.length - 1; i >= 0; i--) {
          if (prev[i].type === 'assistant') {
            if (typeof prev[i].durationMs === 'number') return prev;
            const next = [...prev];
            next[i] = { ...next[i], durationMs };
            return next;
          }
        }
        return prev;
      });
      setLoadingStartTime(null);
    }

    setLoading(isLoading);
  };

  const showThinkingStatus = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const isThinkingVal = isTruthy(payload?.thinking);
    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0 || Boolean(window.__sessionTabsStore);
    const isBackground = hasTabs && targetSessionId && (
      (tabStore.size() > 0 && !tabStore.isActiveSession(targetSessionId)) ||
      (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId))
    );
    if (isBackground && targetSessionId) {
      tabStore.updateBackgroundTab(targetSessionId, (t) => ({ ...t, isThinking: isThinkingVal }));
      window.__sessionTabsStore?.updateBackgroundTab?.(
        (t) => t.sessionId === targetSessionId,
        (t) => {
          t.isThinking = isThinkingVal;
        }
      );
      return;
    }
    setIsThinking(isThinkingVal);
  };

  const showSummary = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const summary = String(payload?.summary ?? '');
    if (!summary || !summary.trim()) return;
    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0 || Boolean(window.__sessionTabsStore);
    const isBackground = hasTabs && targetSessionId && (
      (tabStore.size() > 0 && !tabStore.isActiveSession(targetSessionId)) ||
      (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId))
    );
    if (isBackground && targetSessionId) {
      tabStore.updateBackgroundTab(targetSessionId, (t) => ({ ...t, status: summary }));
      window.__sessionTabsStore?.updateBackgroundTab?.(
        (t) => t.sessionId === targetSessionId,
        (t) => {
          t.status = summary;
        }
      );
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
  const patchUuidInList = (prev: ClaudeMessage[], content: string, uuid: string): ClaudeMessage[] => {
    for (let i = prev.length - 1; i >= 0; i -= 1) {
      const message = prev[i];
      if (message.type !== 'user') continue;
      if (getRawUuid(message)) continue;

      const rawText = extractRawBlocks(message.raw)
        .filter((block) => block?.type === 'text' && typeof block.text === 'string')
        .map((block) => String(block.text))
        .join('\n');
      if ((message.content || '') !== content && rawText !== content) continue;

      const raw: ClaudeMessage['raw'] =
        typeof message.raw === 'object' && message.raw
          ? { ...message.raw, uuid }
          : {
              uuid,
              message: {
                content: [{ type: 'text' as const, text: message.content || content }],
              },
            };

      const next = [...prev];
      next[i] = {
        ...message,
        raw,
      };
      return next;
    }

    cardDebugLog('[patchMessageUuid] no matching unresolved user message found for content:', content);
    return prev;
  };

  const patchMessageUuid = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId = resolveSessionId(env?.sessionId ?? null, 'patchMessageUuid');
    const content = payload?.content as string;
    const uuid = payload?.uuid as string;
    if (!targetSessionId || !content || !uuid) return;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0 || Boolean(window.__sessionTabsStore);
    const isBackground = hasTabs && targetSessionId && (
      (tabStore.size() > 0 && !tabStore.isActiveSession(targetSessionId)) ||
      (window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId))
    );
    if (isBackground && targetSessionId) {
      const applyPatch = (t: any) => {
        const nextMsgs = patchUuidInList(t.messages ?? [], content, uuid);
        t.messages = nextMsgs;
        return { ...t, messages: nextMsgs };
      };
      let written = false;
      if (tabStore.size() > 0) {
        written = tabStore.updateBackgroundTab(targetSessionId, applyPatch as any);
      }
      if (!written && window.__sessionTabsStore) {
        window.__sessionTabsStore.updateBackgroundTab(
          (t) => t.sessionId === targetSessionId,
          applyPatch
        );
      }
      return;
    }

    if (isSessionTransitioning(null)) return;
    setMessages((prev) => patchUuidInList(prev, content, uuid));
  };

  const clearMessages = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const barrierSequenceArg = payload?.sequence as string | number | undefined;

    if (targetSessionId && window.__sessionTabsStore && !window.__sessionTabsStore.isActiveSession(targetSessionId)) {
      window.__sessionTabsStore.updateBackgroundTab(
        (t) => t.sessionId === targetSessionId,
        (t) => {
          t.messages = [];
          t.loading = false;
          t.streamingActive = false;
        }
      );
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
    window.__opencodeHistoryWindow = undefined;
    window.__opencodeListStart = 0;
    window.__prependedHistoryMessageCount = 0;
    window.__messageBaseIndex = 0;
    window.__lastAcceptedMessageCount = undefined;
    window.__pendingHistoryRefreshMessageCount = undefined;
    window.__historySurfaceRefreshEpoch = (window.__historySurfaceRefreshEpoch ?? 0) + 1;
    resetTransientUiState();
    closeContextUsageDialog();
    setMessages([]);
  };

  const addErrorMessage = (message: string) => {
    addToast(message, 'error');
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
    setMessages((prev) => [...prev, message]);
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
    let orphanIds: string[] = [];
    setMessages((prev) => {
      if (prev.length === 0) return prev;
      orphanIds = collectUnresolvedToolUseIds(prev, 'all');
      // Shallow copy forces ChatMessages to re-render so the now-denied IDs are
      // picked up by BashToolGroupBlock's deniedToolIds prop.
      return prev.map(m => ({ ...m }));
    });
    for (const id of orphanIds) {
      window.__deniedToolIds.add(id);
    }
  };

  const historyLoadComplete = (payload: HostEventPayload | undefined, env?: HostEventEnvelope) => {
    const targetSessionId: string | null = env?.sessionId ?? null;
    const expectedMessageCountArg = payload?.messageCount as string | number | undefined;

    const tabStore = getTabStore();
    const hasTabs = tabStore.size() > 0 || Boolean(window.__sessionTabsStore);
    const isForeground = !hasTabs
      || !targetSessionId
      || (targetSessionId === options.currentSessionIdRef?.current)
      || (tabStore.size() > 0 ? tabStore.isActiveSession(targetSessionId) : false)
      || (Boolean(window.__sessionTabsStore?.isActiveSession(targetSessionId)));

    if (!isForeground && targetSessionId && hasTabs) {
      let updated = false;
      if (tabStore.size() > 0) {
        updated = tabStore.updateBackgroundTab(targetSessionId, (t) => ({
          ...t,
          loading: false,
          streamingActive: false,
        }));
      }
      if (!updated && window.__sessionTabsStore) {
        window.__sessionTabsStore.updateBackgroundTab(
          (t) => t.sessionId === targetSessionId,
          (t) => {
            t.loading = false;
            t.streamingActive = false;
          }
        );
      }
      // If the tab that owns this session is transitioning toward it, the
      // guard is released now that the history has arrived.
      const ownerTabId = tabStore.findIdBySession(targetSessionId) ?? window.__sessionTabsStore?.getTabIdForSession?.(targetSessionId) ?? null;
      if (window.__sessionTabsStore?.getTabTransitionTarget?.(ownerTabId) === targetSessionId) {
        window.__sessionTabsStore?.endTabTransition?.(ownerTabId);
      }
      // If the foreground active tab is not currently loading, ensure front-end loading bar is not stranded
      options.setSessionLoading(false);
      return;
    }

    cardDebugLog('[HistoryLoadComplete] called, transitioning BEFORE release:', isSessionTransitioning(targetSessionId), 'expectedMsgCount:', expectedMessageCountArg);
    cardDebugLog('[HistoryLoadComplete] stack:', new Error().stack?.split('\n').slice(1, 4).join(' | '));
    endTransition(activeTabId());
    cardDebugLog('[HistoryLoadComplete] transitioning AFTER release:', isSessionTransitioning(targetSessionId));
    // Immediately release sessionLoading flag
    options.setSessionLoading(false);
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
        : (window.__sessionTabsStore
          ? window.__sessionTabsStore.isActiveSession(sessionId)
          : currentSessionIdRef.current === sessionId); // legacy-allow: fallback when no tab strip exists
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

      if (mode === 'replace') {
        setMessages(buffered);
        window.__prependedHistoryMessageCount = 0;
      } else {
        // prepend
        setMessages((prev) => [...buffered, ...prev]);
        window.__prependedHistoryMessageCount =
          (window.__prependedHistoryMessageCount ?? 0) + buffered.length;
      }

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

      // Adjust streaming index for prepended messages
      if (mode === 'prepend' && options.streamingMessageIndexRef.current >= 0) {
        options.streamingMessageIndexRef.current += buffered.length;
      }
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
    setMessages((prev) => {
      // If the last message is an optimistic message with matching content,
      // skip adding — the frontend already rendered the optimistic copy.
      // Otherwise addUserMessage + optimistic create a brief duplicate until
      // the next updateMessages deduplicates them.
      const lastMsg = prev[prev.length - 1];
      if (lastMsg?.isOptimistic && lastMsg.type === 'user' && lastMsg.content === content) {
        return prev;
      }
      return [...prev, userMessage];
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
        isStreaming,
        status,
        loading,
        revertBoundaryId,
      } = packet;

      if (!targetSessionId || !Array.isArray(authoritativeMessages)) {
        return;
      }

      cardDebugLog('[onSessionHydrated] received for session:', targetSessionId, 'count:', authoritativeMessages.length);

      const tabStore = getTabStore();
      const activeSessionId = tabStore.getActiveSessionId() || window.__sessionTabsStore?.getActiveSessionId?.() || currentSessionIdRef?.current;
      const isForeground = !activeSessionId || targetSessionId === activeSessionId;

      if (isForeground) {
        setMessages(authoritativeMessages);
        window.__messageBaseIndex = 0;
        window.__opencodeListStart = 0;

        if (typeof isStreaming === 'boolean') {
          isStreamingRef.current = isStreaming;
          setStreamingActive?.(isStreaming);
          if (isStreaming) {
            const lastAssistantIdx = findLastAssistantIndex(authoritativeMessages);
            if (lastAssistantIdx >= 0) {
              streamingMessageIndexRef.current = lastAssistantIdx;
              const lastAssistant = authoritativeMessages[lastAssistantIdx];
              streamingContentRef.current = lastAssistant?.content || '';
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
        t.messages = authoritativeMessages;
        t.messageBaseIndex = 0;
        if (typeof isStreaming === 'boolean') t.streamingActive = isStreaming;
        if (typeof loading === 'boolean') t.loading = loading;
        if (typeof status === 'string') t.status = status;
        if (typeof revertBoundaryId !== 'undefined') t.revertBoundaryId = revertBoundaryId;
        return { ...t };
      };
      if (tabStore.size() > 0) {
        tabStore.updateBackgroundTab(targetSessionId, applyHydrated as any);
      }
      if (window.__sessionTabsStore) {
        window.__sessionTabsStore.updateBackgroundTab(
          (t) => t.sessionId === targetSessionId,
          applyHydrated
        );
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
