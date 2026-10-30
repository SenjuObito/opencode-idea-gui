import { describe, expect, it } from 'vitest';
import { TabStatusStore } from './tabStatusStore';

describe('per-tab status store', () => {
  it('keeps one status snapshot per tab and forgets a closed tab', () => {
    const store = new TabStatusStore();
    const statusA = {
      loading: true,
      streamingActive: true,
      isThinking: false,
      status: 'running',
      revertBoundaryId: null,
      isCompacting: false,
      compactingStartTime: null,
      taskEvents: {},
      sseTodos: null,
      subagentHistories: {},
      filteredFileChanges: [],
      turnStartedAt: 1,
      usagePercentage: 10,
    };

    store.set('tab-a', statusA);
    store.set('tab-b', { ...statusA, loading: false, status: '' });

    expect(store.get('tab-a')).toBe(statusA);
    expect(store.get('tab-b')?.status).toBe('');

    store.delete('tab-a');
    expect(store.get('tab-a')).toBeUndefined();
    expect(store.size()).toBe(1);
  });
});
