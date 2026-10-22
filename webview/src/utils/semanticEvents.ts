/**
 * The semantic event catalog, webview side.
 *
 * <p>The host names an event semantically ({@code content.delta},
 * {@code message.snapshot}, {@code turn.ended}); this table says which handler
 * answers it. A type that is not listed falls back to the legacy
 * {@code window.<fn>} lookup, so the protocol can be completed step by step
 * without a flag day.</p>
 *
 * <p>Keep this table in sync with {@code com.opencodebuddy.bridge.EventCatalog}
 * — the Java test {@code EventCatalogTest} asserts that both sides agree.</p>
 */
export const SEMANTIC_HANDLERS: Record<string, string> = {
  // ── lifecycle ──────────────────────────────────────────────────────
  'tab.resync': 'onTabResync',
  'session.bound': 'setSessionId',
  'session.loaded': 'historyLoadComplete',
  'session.cleared': 'clearMessages',
  'command.rejected': 'commandRejected',
  'attachment.failed': 'onAttachmentFailed',

  // ── turn & message ─────────────────────────────────────────────────
  'turn.started': 'onStreamStart',
  'turn.ended': 'onStreamEnd',
  'turn.settled': 'onTurnEnd',
  'turn.retry': 'updateRetryStatus',
  'turn.heartbeat': 'onStreamingHeartbeat',
  'content.delta': 'onContentDelta',
  'thinking.delta': 'onThinkingDelta',
  'thinking.status': 'showThinkingStatus',
  'message.snapshot': 'updateMessages',
  'message.tail': 'updateMessageTail',
  'message.prepended': 'updateMessagesPrepend',
  'message.added': 'addUserMessage',
  'message.history': 'addHistoryMessage',
  'message.removed': 'onMessagesRemoved',
  'message.uuid.patched': 'patchMessageUuid',
  'message.block.reset': 'onBlockReset',

  // ── status & usage ─────────────────────────────────────────────────
  'status.updated': 'updateStatus',
  'session.loading': 'showLoading',
  'session.summary': 'showSummary',
  'usage.updated': 'onUsageUpdate',

  // ── tab & settings ─────────────────────────────────────────────────
  'tab.title.changed': 'updateSessionTitle',
  'settings.mode': 'onModeChanged',

  // ── prompts ────────────────────────────────────────────────────────
  'permission.requested': 'showPermissionDialog',
  'question.requested': 'showAskUserQuestionDialog',
  'question.answered': 'onQuestionAnswered',
  'plan.requested': 'showPlanApprovalDialog',
  'prompt.closed': 'forceClosePermissionDialog',
  'prompt.question.closed': 'forceCloseAskUserQuestionDialog',
  'prompt.plan.closed': 'forceClosePlanApprovalDialog',

  // ── status panel ───────────────────────────────────────────────────
  'todos.updated': 'onTodoUpdated',
  'task.event': 'onTaskEvent',
  'subagent.history': 'onSubagentHistoryLoaded',
  'revert.changed': 'onRevertStateUpdate',
};

/**
 * The handler name for a semantic event type.
 *
 * @returns the window handler, or {@code null} when the type is unknown
 */
export function handlerForSemanticType(type: string): string | null {
  if (!type) return null;
  return SEMANTIC_HANDLERS[type] ?? null;
}
