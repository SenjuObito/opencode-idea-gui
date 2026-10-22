import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  dispatchHostEvent,
  flushHostDispatcher,
  pendingHostEventCount,
} from './hostDispatcher';

describe('hostDispatcher', () => {
  beforeEach(() => {
    flushHostDispatcher();
    delete (window as any).earlyEvent;
    delete (window as any).__nested;
  });

  it('queues an event until its handler is registered, then replays it in order', () => {
    dispatchHostEvent('earlyEvent', ['first']);
    dispatchHostEvent('earlyEvent', ['second']);

    const handler = vi.fn();
    (window as any).earlyEvent = handler;
    flushHostDispatcher();

    expect(handler.mock.calls).toEqual([['first'], ['second']]);
    expect(pendingHostEventCount()).toBe(0);
  });

  it('dispatches immediately to an existing handler and supports window-qualified names', () => {
    const handler = vi.fn();
    (window as any).earlyEvent = handler;

    dispatchHostEvent('window.earlyEvent', [42]);

    expect(handler).toHaveBeenCalledWith(42);
    expect(pendingHostEventCount()).toBe(0);
  });

  it('resolves nested callback paths', () => {
    const handler = vi.fn();
    (window as any).__nested = { handler };

    dispatchHostEvent('__nested.handler', ['x']);

    expect(handler).toHaveBeenCalledWith('x');
  });

  it('keeps events without handlers queued and does not throw', () => {
    dispatchHostEvent('stillMissing', ['x']);
    flushHostDispatcher();

    expect(pendingHostEventCount()).toBe(1);
  });

  it('bounds the pre-mount queue under a large event burst', () => {
    for (let i = 0; i < 2_100; i++) {
      dispatchHostEvent('notMountedYet', [i]);
    }

    expect(pendingHostEventCount()).toBeLessThanOrEqual(2_000);
    flushHostDispatcher();
    (window as any).notMountedYet = vi.fn();
    flushHostDispatcher();
    expect(pendingHostEventCount()).toBe(0);
  });
});
