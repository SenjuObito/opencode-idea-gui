import type { SessionTabData } from '../components/ChatHeader/types';

type Updater<T> = T | ((previous: T) => T);

/** Stable, collision-free webview tab id. Never derive it from a timestamp alone. */
export function createTabId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `tab-${crypto.randomUUID()}`;
  }
  return `tab-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * The webview's authoritative view state: the ordered tabs and the active tab.
 *
 * The host never owns this list; it only receives `tab_activate` notifications.
 * Keeping it in a small external store (instead of the big session hook) makes
 * "what is rendered" independent from streaming/message plumbing and gives the
 * dispatcher one stable place to read tab state from.
 */
export class TabStore {
  private tabs: SessionTabData[];
  private activeTabId: string;
  private readonly listeners = new Set<() => void>();
  private updatingTabs = false;
  private activeChangedDuringUpdate = false;

  constructor(initialTabs: SessionTabData[], activeTabId: string) {
    this.tabs = initialTabs;
    this.activeTabId = activeTabId;
  }

  readonly getTabs = (): SessionTabData[] => this.tabs;

  readonly getActiveTabId = (): string => this.activeTabId;

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  readonly setTabs = (updater: Updater<SessionTabData[]>): void => {
    this.updatingTabs = true;
    let next: SessionTabData[];
    try {
      next = typeof updater === 'function'
        ? (updater as (previous: SessionTabData[]) => SessionTabData[])(this.tabs)
        : updater;
    } finally {
      this.updatingTabs = false;
    }
    const tabsChanged = next !== this.tabs;
    if (tabsChanged) {
      this.tabs = next;
    }
    if (tabsChanged || this.activeChangedDuringUpdate) {
      this.activeChangedDuringUpdate = false;
      this.emit();
    }
  };

  readonly setActiveTabId = (updater: Updater<string>): void => {
    const next = typeof updater === 'function'
      ? (updater as (previous: string) => string)(this.activeTabId)
      : updater;
    if (next === this.activeTabId) return;
    this.activeTabId = next;
    if (this.updatingTabs) {
      // The tabs updater often switches the active id before returning the new
      // list. Emit once after both snapshots are consistent.
      this.activeChangedDuringUpdate = true;
      return;
    }
    this.emit();
  };

  findById(tabId: string | null | undefined): SessionTabData | undefined {
    if (!tabId) return undefined;
    return this.tabs.find((tab) => tab.id === tabId);
  }

  findIdBySession(sessionId: string | null | undefined): string | null {
    if (!sessionId) return null;
    return this.tabs.find(
      (tab) => tab.sessionId === sessionId || tab.id === sessionId,
    )?.id ?? null;
  }

  private emit(): void {
    for (const listener of this.listeners) {
      listener();
    }
  }
}
