import { describe, expect, it, vi } from 'vitest';
import { TabStore, createTabId } from './tabStore';
import type { SessionTabData } from '../components/ChatHeader/types';

const tab = (id: string, sessionId: string | null = null): SessionTabData => ({
  id,
  sessionId,
  title: id,
  messages: [],
});

describe('TabStore', () => {
  it('creates unique stable tab ids instead of timestamp-only ids', () => {
    const ids = new Set(Array.from({ length: 100 }, () => createTabId()));
    expect(ids.size).toBe(100);
  });

  it('notifies subscribers only when tabs or the active id actually change', () => {
    const store = new TabStore([tab('tab-1')], 'tab-1');
    const listener = vi.fn();
    store.subscribe(listener);

    store.setActiveTabId('tab-1');
    expect(listener).not.toHaveBeenCalled();

    store.setActiveTabId('tab-2');
    expect(listener).toHaveBeenCalledTimes(1);

    const current = store.getTabs();
    store.setTabs(current);
    expect(listener).toHaveBeenCalledTimes(1);

    store.setTabs((prev) => [...prev, tab('tab-2')]);
    expect(listener).toHaveBeenCalledTimes(2);
  });

  it('emits once after a tabs updater also switches the active id', () => {
    const store = new TabStore([tab('tab-1')], 'tab-1');
    const snapshots: Array<[string, string[]]> = [];
    store.subscribe(() => {
      snapshots.push([store.getActiveTabId(), store.getTabs().map((item) => item.id)]);
    });

    store.setTabs((prev) => {
      store.setActiveTabId('tab-2');
      return [...prev, tab('tab-2')];
    });

    expect(snapshots).toEqual([['tab-2', ['tab-1', 'tab-2']]]);
  });

  it('resolves tabs by both stable tab id and session id', () => {
    const store = new TabStore([tab('tab-1', 'ses_a'), tab('tab-2')], 'tab-1');

    expect(store.findById('tab-2')?.id).toBe('tab-2');
    expect(store.findIdBySession('ses_a')).toBe('tab-1');
    expect(store.findIdBySession('tab-2')).toBe('tab-2');
    expect(store.findIdBySession('ses_missing')).toBeNull();
  });

  it('stops notifying after unsubscribe', () => {
    const store = new TabStore([tab('tab-1')], 'tab-1');
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);

    unsubscribe();
    store.setActiveTabId('tab-2');

    expect(listener).not.toHaveBeenCalled();
  });
});
