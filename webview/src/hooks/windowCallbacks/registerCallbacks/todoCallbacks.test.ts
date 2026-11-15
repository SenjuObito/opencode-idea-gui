import { describe, it, expect, beforeEach, vi } from 'vitest';
import { registerWindowCallbacks } from '../registerCallbacks';
import { hostEventHandler } from '../../../utils/hostEventRegistry';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { plainEvent, sessionEvent } from '../../../utils/testEvents';
import { getTabStore } from '../../../store/tabStore';
import { seedTabStore } from '../../../test/seedTabStore';

type Ref<T> = { current: T };
const ref = <T,>(value: T): Ref<T> => ({ current: value });

/**
 * `todos.updated` routes by tab id only.
 *
 * <p>The host owns the session↔tab binding and stamps the owning tab on every
 * tab-scoped event, so this layer never reverse-looks-up a session. These cases
 * pin that contract: the event's tab decides where the list lands, a tab this
 * view does not know is dropped instead of overwriting what is on screen, and
 * an empty list is a real engine state.</p>
 */
describe('todos.updated', () => {
  let setSseTodos: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    setSseTodos = vi.fn();
    seedTabStore([
      { id: 'tab-active', sessionId: 'ses_active' },
      { id: 'tab-bg', sessionId: 'ses_bg' },
    ], 'tab-active');

    const options = {
      currentSessionIdRef: ref<string | null>('ses_active'),
      setSseTodos,
      streamingContentRef: ref(''),
      streamingThinkingRef: ref(''),
      isStreamingRef: ref(false),
      currentProviderRef: ref('opencode'),
      selectedModelRef: ref('default'),
    } as unknown as UseWindowCallbacksOptions;

    registerWindowCallbacks(options, vi.fn());
  });

  it('writes the list onto the tab the event names', () => {
    const payload = JSON.stringify({
      sessionID: 'ses_active',
      todos: [
        { id: '1', content: 'Task 1', status: 'completed' },
        { id: '2', content: 'Task 2', status: 'in_progress' },
      ],
    });

    hostEventHandler('todos.updated')!(...sessionEvent('ses_active', { todos: payload }, 'tab-active'));

    expect(getTabStore().findById('tab-active')?.sseTodos).toEqual([
      { id: '1', content: 'Task 1', status: 'completed' },
      { id: '2', content: 'Task 2', status: 'in_progress' },
    ]);
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

    hostEventHandler('todos.updated')!(...sessionEvent('ses_active', { todos: doubleEncoded }, 'tab-active'));

    const calledTodos = getTabStore().findById('tab-active')?.sseTodos ?? [];
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

  it('routes a background tab update to that tab, leaving the active one alone', () => {
    const payload = JSON.stringify({
      sessionID: 'ses_bg',
      todos: [{ id: '1', content: 'Bg Task', status: 'pending' }],
    });

    hostEventHandler('todos.updated')!(...sessionEvent('ses_bg', { todos: payload }, 'tab-bg'));

    expect(setSseTodos).not.toHaveBeenCalled();
    expect(getTabStore().findById('tab-bg')?.sseTodos?.[0].content).toBe('Bg Task');
    expect(getTabStore().findById('tab-active')?.sseTodos ?? null).toBeNull();
  });

  it('trusts the envelope over the payload session id', () => {
    // A stale publisher can carry a session whose tab is not the envelope's;
    // the host already re-addressed the event, so the envelope wins.
    const payload = JSON.stringify({
      sessionID: 'ses_bg',
      todos: [{ content: 'Readdressed', status: 'pending' }],
    });

    hostEventHandler('todos.updated')!(...sessionEvent('ses_active', { todos: payload }, 'tab-active'));

    expect(getTabStore().findById('tab-active')?.sseTodos?.[0].content).toBe('Readdressed');
    expect(getTabStore().findById('tab-bg')?.sseTodos ?? null).toBeNull();
  });

  it('drops an update for a tab this view does not know', () => {
    hostEventHandler('todos.updated')!(...sessionEvent('ses_unknown', {
      todos: JSON.stringify({ todos: [{ content: 'Stray', status: 'pending' }] }),
    }, 'tab-unknown'));

    expect(setSseTodos).not.toHaveBeenCalled();
    expect(getTabStore().findById('tab-active')?.sseTodos ?? null).toBeNull();
  });

  it('treats an empty list as a cleared state', () => {
    getTabStore().updateTab('tab-active', (tab) => ({
      ...tab,
      sseTodos: [{ id: '1', content: 'Old', status: 'pending' }],
    }));

    hostEventHandler('todos.updated')!(...sessionEvent('ses_active', { todos: '[]' }, 'tab-active'));

    expect(getTabStore().findById('tab-active')?.sseTodos).toEqual([]);
  });

  it('sends a legacy event with no tab id to the active view only', () => {
    hostEventHandler('todos.updated')!(...plainEvent({
      todos: JSON.stringify({ todos: [{ content: 'Legacy', status: 'pending' }] }),
    }));

    expect(setSseTodos).toHaveBeenCalledTimes(1);
    expect(setSseTodos.mock.calls[0][0]).toEqual([
      { id: '0', content: 'Legacy', status: 'pending' },
    ]);
  });

  it('silently ignores non-array todos without throwing', () => {
    const payload = JSON.stringify({
      sessionID: 'ses_active',
      todos: 'not-an-array',
    });

    expect(() => hostEventHandler('todos.updated')!(...sessionEvent('ses_active', { todos: payload }, 'tab-active'))).not.toThrow();
    expect(setSseTodos).not.toHaveBeenCalled();
    expect(getTabStore().findById('tab-active')?.sseTodos ?? null).toBeNull();
  });
});
