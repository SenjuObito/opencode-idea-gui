import { describe, expect, it } from 'vitest';
import { parseTabQueuePayload } from './tabQueueStore';

describe('parseTabQueuePayload', () => {
  it('reads the canonical text field', () => {
    const snapshot = parseTabQueuePayload({
      items: [{ id: 'q1', text: 'hello queue', createdAt: 10 }],
      paused: false,
      lastError: null,
    });

    expect(snapshot?.items).toHaveLength(1);
    expect(snapshot?.items[0].content).toBe('hello queue');
  });

  it('falls back to content so a mixed install still shows text', () => {
    const snapshot = parseTabQueuePayload({
      items: [{ id: 'q1', content: 'legacy field', createdAt: 10 }],
    });

    expect(snapshot?.items[0].content).toBe('legacy field');
  });

  it('drops items without an id instead of rendering empty rows', () => {
    const snapshot = parseTabQueuePayload({
      items: [{ text: 'no id' }, { id: 'q1', text: 'kept' }],
    });

    expect(snapshot?.items.map((item) => item.id)).toEqual(['q1']);
  });

  it('normalizes the paused flag and the last error', () => {
    const snapshot = parseTabQueuePayload({
      items: [],
      paused: true,
      lastError: { itemId: 'q1', message: 'boom' },
    });

    expect(snapshot?.paused).toBe(true);
    expect(snapshot?.lastError).toEqual({ itemId: 'q1', message: 'boom' });
  });
});
