import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';
import type { ClaudeRawMessage, SubagentHistoryResponse, TaskEvent, TaskEventMap } from '../types';

// SubagentHistoryContext holds the full transcript map so consumers
// (AgentGroupBlock / TaskExecutionBlock / StatusPanel details) re-render when an
// entry arrives. The map is small (one entry per expanded row) and updates are
// low-frequency (one request per expansion, no polling), so re-rendering all
// inline cards on a change is bounded and matches the TaskEventContext pattern
// below.
//
// A transcript is detail data only. It is a point-in-time snapshot and must
// never decide a run's status; `TaskEventContext` (fed by core's
// `subagent.status`) is the single status source.
const SubagentHistoryContext = createContext<Record<string, SubagentHistoryResponse>>({});

interface SessionIdContextValue {
  currentSessionId: string | null;
  currentProvider: string;
}

const SessionIdContext = createContext<SessionIdContextValue>({
  currentSessionId: null,
  currentProvider: 'claude',
});

export type GetToolResultRawFn = (toolUseId: string) => ClaudeRawMessage | null;

const ToolResultRawContext = createContext<GetToolResultRawFn>(() => null);

// TaskEventContext holds the canonical terminal run-event map (keyed by
// tool_use_id) so the StatusPanel and both inline Agent cards derive status from
// one place. Core publishes `subagent.status` for inline and background calls
// alike; Claude's task_notification feeds the same map. This deliberately
// re-renders consumers on change: the event arrives after the launch/parent
// result, and the row must leave "running" without a manual reload. The map is
// small (one entry per finished run) and updates are terminal-only, so the
// re-render cost is bounded.
//
// Split into its own provider (TaskEventProvider) rather than living in
// MessagesContext so that run-event updates do not invalidate the
// MessagesContext value and re-render every consumer of it.
const TaskEventContext = createContext<TaskEventMap>({});

// SetTaskEventContext is separated from TaskEventContext so that components
// which only need the setter (App.tsx, useSessionManagement, useWindowCallbacks)
// do not re-render when the map changes.
const SetTaskEventContext = createContext<React.Dispatch<React.SetStateAction<TaskEventMap>> | null>(null);

/**
 * Provider that owns the task-events map state. Mount once above the App so
 * both the setter (consumed by App/session-management/window-callbacks) and the
 * map (consumed by inline Agent cards and useSubagents) share a single source.
 */
export function TaskEventProvider({ children }: { children: ReactNode }) {
  const [taskEvents, setTaskEvents] = useState<TaskEventMap>({});
  return (
    <SetTaskEventContext.Provider value={setTaskEvents}>
      <TaskEventContext.Provider value={taskEvents}>
        {children}
      </TaskEventContext.Provider>
    </SetTaskEventContext.Provider>
  );
}

/**
 * Read the task-events map. Re-renders the caller when any entry changes.
 */
export function useTaskEvents(): TaskEventMap {
  return useContext(TaskEventContext);
}

/**
 * Read the setter for the task-events map. Stable identity; does NOT re-render
 * the caller when the map changes. Throws if used outside TaskEventProvider.
 */
export function useSetTaskEvents(): React.Dispatch<React.SetStateAction<TaskEventMap>> {
  const setTaskEvents = useContext(SetTaskEventContext);
  if (!setTaskEvents) {
    throw new Error('useSetTaskEvents must be used within a TaskEventProvider');
  }
  return setTaskEvents;
}

/**
 * Read the full subagent-history map. Re-renders the caller whenever any entry
 * changes, so an expanded inline Agent card reflects a freshly loaded sidechain
 * transcript without a manual reload. Callers narrow to the key they care about
 * (toolUseId, falling back to agentId).
 */
export function useSubagentHistories(): Record<string, SubagentHistoryResponse> {
  return useContext(SubagentHistoryContext);
}

/**
 * Hook for reading the current session ID provided via SessionIdContext.
 */
export function useSessionId(): string | null {
  return useContext(SessionIdContext).currentSessionId;
}

/** Current provider paired with the session ID for provider-specific history reads. */
export function useSessionProvider(): string {
  return useContext(SessionIdContext).currentProvider;
}

/**
 * Hook for looking up the raw message that contains a given tool result.
 */
export function useGetToolResultRaw(): GetToolResultRawFn {
  return useContext(ToolResultRawContext);
}

/**
 * Hook returning the canonical terminal run event for a subagent call, keyed by
 * its tool_use_id (inline and background alike). Re-renders the caller when the
 * event map changes so the cards reflect completion in real time.
 */
export function useTaskEvent(toolUseId: string | undefined): TaskEvent | undefined {
  const taskEvents = useContext(TaskEventContext);
  if (!toolUseId) return undefined;
  return taskEvents[toolUseId];
}

/**
 * Creates the context value objects used by App.tsx's Providers.
 * `subagentHistoryCtxValue` is the raw map so Provider consumers re-render when
 * an entry arrives (see useSubagentHistories); its identity changes only when
 * `subagentHistories` changes. `sessionIdCtxValue` is memoized on
 * `currentSessionId` so SessionIdContext consumers don't re-render on every
 * history update.
 */
export function useSubagentContextValues(
  subagentHistories: Record<string, SubagentHistoryResponse>,
  currentSessionId: string | null,
  currentProvider: string,
) {
  const sessionIdCtxValue = useMemo(
    () => ({ currentSessionId, currentProvider }),
    [currentProvider, currentSessionId],
  );
  return { subagentHistoryCtxValue: subagentHistories, sessionIdCtxValue };
}

export { SubagentHistoryContext, SessionIdContext, ToolResultRawContext };
