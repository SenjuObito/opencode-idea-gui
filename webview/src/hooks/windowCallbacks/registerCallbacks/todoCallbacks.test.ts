import { describe, it, expect, beforeEach, vi } from 'vitest';
import { registerWindowCallbacks } from '../registerCallbacks';
import { hostEventHandler } from '../../../utils/hostEventRegistry';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { TodoItem } from '../../../types';
import { sessionEvent } from '../../../utils/testEvents';

type Ref<T> = { current: T };
const ref = <T,>(value: T): Ref<T> => ({ current: value });

describe('todos.updated', () => {
  let setSseTodos: ReturnType<typeof vi.fn>;
  let currentSessionIdRef: Ref<string | null>;
  let updateBackgroundTab: ReturnType<typeof vi.fn>;
  let isActiveSession: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    delete (window as any).__sessionTabsStore;

    setSseTodos = vi.fn();
    currentSessionIdRef = ref<string | null>('ses_active');
    updateBackgroundTab = vi.fn();
    isActiveSession = vi.fn((sid: string | null | undefined) => sid === 'ses_active');

    (window as any).__sessionTabsStore = {
      isActiveSession,
      updateBackgroundTab,
    };

    const options = {
      currentSessionIdRef,
      setSseTodos,
      streamingContentRef: ref(''),
      streamingThinkingRef: ref(''),
      isStreamingRef: ref(false),
      currentProviderRef: ref('opencode'),
      selectedModelRef: ref('default'),
    } as unknown as UseWindowCallbacksOptions;

    registerWindowCallbacks(options, vi.fn());
  });

  it('updates active session todos with single-encoded JSON', () => {
    const payload = JSON.stringify({
      sessionID: 'ses_active',
      todos: [
        { id: '1', content: 'Task 1', status: 'completed' },
        { id: '2', content: 'Task 2', status: 'in_progress' },
      ],
    });

    hostEventHandler('todos.updated')!(...sessionEvent('ses_active', { todos: payload }));

    expect(setSseTodos).toHaveBeenCalledTimes(1);
    const calledTodos: TodoItem[] = setSseTodos.mock.calls[0][0];
    expect(calledTodos).toHaveLength(2);
    expect(calledTodos[0]).toEqual({
      id: '1',
      content: 'Task 1',
      status: 'completed',
    });
    expect(calledTodos[1]).toEqual({
      id: '2',
      content: 'Task 2',
      status: 'in_progress',
    });
  });

  it('defensively unwraps double-encoded JSON and normalizes statuses', () => {
    const innerJson = JSON.stringify({
      sessionId: 'ses_active',
      todos: [
        { id: '10', content: 'Task 10', status: 'cancelled' },
        { id: '11', title: 'Task 11', status: 'running' },
        { id: '12', step: 'Task 12', status: 'pending' },
      ],
    });
    const doubleEncoded = JSON.stringify(innerJson);

    hostEventHandler('todos.updated')!(...sessionEvent('ses_active', { todos: doubleEncoded }));

    expect(setSseTodos).toHaveBeenCalledTimes(1);
    const calledTodos: TodoItem[] = setSseTodos.mock.calls[0][0];
    expect(calledTodos).toHaveLength(3);
    // v2 has a real cancelled state; it must not be rewritten to completed
    expect(calledTodos[0].status).toBe('cancelled');
    expect(calledTodos[0].content).toBe('Task 10');
    // 'running' should normalize to 'in_progress'
    expect(calledTodos[1].status).toBe('in_progress');
    expect(calledTodos[1].content).toBe('Task 11');
    // 'pending' should remain 'pending'
    expect(calledTodos[2].status).toBe('pending');
    expect(calledTodos[2].content).toBe('Task 12');
  });

  it('routes updates for background sessions to sessionTabsStore', () => {
    const payload = JSON.stringify({
      sessionID: 'ses_bg',
      todos: [{ id: '1', content: 'Bg Task', status: 'pending' }],
    });

    hostEventHandler('todos.updated')!(...sessionEvent('ses_bg', { todos: payload }));

    expect(setSseTodos).not.toHaveBeenCalled();
    expect(updateBackgroundTab).toHaveBeenCalledTimes(1);

    // Verify predicate and updater
    const tabObj: any = { sessionId: 'ses_bg', sseTodos: null };
    const [predicate, updater] = updateBackgroundTab.mock.calls[0];
    expect(predicate(tabObj)).toBe(true);
    updater(tabObj);
    expect(tabObj.sseTodos).toHaveLength(1);
    expect(tabObj.sseTodos[0].content).toBe('Bg Task');
  });

  it('silently ignores non-array todos without throwing', () => {
    const payload = JSON.stringify({
      sessionID: 'ses_active',
      todos: 'not-an-array',
    });

    expect(() => hostEventHandler('todos.updated')!(...sessionEvent('ses_active', { todos: payload }))).not.toThrow();
    expect(setSseTodos).not.toHaveBeenCalled();
  });
});
