import { describe, expect, it } from 'vitest';
import { TabRuntimeStore } from './tabRuntimeStore';
import type { StreamingRuntimeSnapshot } from '../utils/streamingRuntime';

const snapshot = (content: string): StreamingRuntimeSnapshot => ({
  isStreaming: true,
  content,
  thinking: '',
  useBackendStreamingRender: false,
  turnId: 1,
  lastContentUpdate: 0,
  lastThinkingUpdate: 0,
  autoExpandedThinkingKeys: [],
  trailingStructuralTextBoundary: null,
  streamEndProcessedTurnId: null,
});

describe('TabRuntimeStore', () => {
  it('keeps runtime snapshots isolated by tab id', () => {
    const store = new TabRuntimeStore();
    store.set('tab-a', snapshot('A'));
    store.set('tab-b', snapshot('B'));

    expect(store.get('tab-a')?.content).toBe('A');
    expect(store.get('tab-b')?.content).toBe('B');
    expect(store.size()).toBe(2);
  });

  it('drops a tab snapshot on close so a recycled id never inherits it', () => {
    const store = new TabRuntimeStore();
    store.set('tab-a', snapshot('A'));

    store.delete('tab-a');

    expect(store.get('tab-a')).toBeUndefined();
    expect(store.size()).toBe(0);
  });
});
