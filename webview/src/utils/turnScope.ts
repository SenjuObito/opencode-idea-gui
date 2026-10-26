import type { ClaudeMessage, TodoItem, SubagentInfo } from '../types';

export function isToolResultOnlyUserMessage(message: ClaudeMessage): boolean {
  if (message.type !== 'user') return false;
  if ((message.content ?? '').trim() === '[tool_result]') return true;

  const raw = message.raw;
  if (!raw || typeof raw === 'string') return false;

  const content = raw.content ?? raw.message?.content;
  if (!Array.isArray(content)) return false;

  return content.some((block) =>
    block && typeof block === 'object' && (block as { type?: string }).type === 'tool_result',
  );
}

export function findLatestConversationTurnStart(messages: ClaudeMessage[]): number {
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const message = messages[i];
    if (message.type !== 'user') continue;
    if (isToolResultOnlyUserMessage(message)) continue;
    return i;
  }
  return -1;
}

export function sliceLatestConversationTurn(messages: ClaudeMessage[]): ClaudeMessage[] {
  const start = findLatestConversationTurnStart(messages);
  return start >= 0 ? messages.slice(start) : [];
}

/**
 * Decide which message slice feeds the StatusPanel-derived lists (subagents,
 * todos). While a turn is streaming, the scope narrows to the latest turn so
 * settled sync tool progress from older turns does not clutter the panel; once
 * settled, the full conversation is shown. A run_in_background agent is the
 * exception: it starts in an older turn but keeps running after that turn
 * settles, and its terminal report lands in a later turn. Narrowing would drop
 * the agent's card while the user waits for it to return, so a session that
 * contains any async agent keeps the full conversation in scope.
 */
export function computeStatusScopeMessages(
  streamingActive: boolean,
  hasAsyncAgents: boolean,
  latestTurnMessages: ClaudeMessage[],
  messages: ClaudeMessage[],
  latestTurnHasToolUse: boolean,
): ClaudeMessage[] {
  if (!streamingActive) return messages;
  if (hasAsyncAgents) return messages;
  return latestTurnMessages.length > 0 && latestTurnHasToolUse ? latestTurnMessages : messages;
}

/**
 * Todos are engine state, not a UI clock.
 *
 * In particular, do not turn `in_progress` into `completed` when a turn
 * settles: a Plan-mode turn can end with work still pending, and v2's
 * `todowrite` statuses are authoritative. The canonical list now comes from
 * core; this function is kept as the one place legacy callers can opt into
 * future finalization rules without reintroducing the old guess.
 */
export function finalizeTodosForSettledTurn(todos: TodoItem[], _isStreaming: boolean): TodoItem[] {
  return todos;
}

export function finalizeSubagentsForSettledTurn(subagents: SubagentInfo[], _isStreaming: boolean): SubagentInfo[] {
  // A settled main turn is not evidence that a run_in_background agent ended:
  // the launch turn completes while the sidechain may still be running. Async
  // agents are finalized only by core's `subagent.status` (fed by the child
  // session or its synthetic notice). Inline agents already receive the same
  // event from core, with the parent tool result as the replay fallback.
  return subagents;
}
