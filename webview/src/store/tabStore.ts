import type { SessionTabData } from '../components/ChatHeader/types';
import type { ClaudeMessage } from '../types';

type Updater<T> = T | ((previous: T) => T);

/** Stable, collision-free webview tab id. Never derive it from a timestamp alone. */
export function createTabId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `tab-${crypto.randomUUID()}`;
  }
  return `tab-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * The tab strip of this webview.
 *
 * <p>One webview hosts one chat window, so the strip has the same lifetime as
 * the JS context: a module-level instance, not one per React mount. The
 * message flow reads the active tab's transcript from here, so a conversation
 * exists exactly once in the webview.</p>
 */
let singleton: TabStore | null = null;

export function getTabStore(): TabStore {
  if (singleton === null) {
    singleton = new TabStore([], '');
  }
  return singleton;
}

/** Drop the strip (tests: a fresh window must not inherit the previous tabs). */
export function resetTabStore(): void {
  singleton = new TabStore([], '');
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
  /** The transcript of a window that has no tab yet. */
  private detachedMessages: ClaudeMessage[] = [];
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

  /** How many tabs the strip holds. */
  size(): number {
    return this.tabs.length;
  }

  findById(tabId: string | null | undefined): SessionTabData | undefined {
    if (!tabId) return undefined;
    return this.tabs.find((tab) => tab.id === tabId);
  }

  /**
   * The transcript of the tab that is on screen.
   *
   * <p>The store owns it: the view reads it from here, so switching tabs
   * never has to copy a transcript into another piece of state.</p>
   */
  getActiveTabMessages(): ClaudeMessage[] {
    const active = this.findById(this.activeTabId);
    // No tab yet (the strip is being built): the transcript waits in the store
    // instead of in a second owner.
    return active ? active.messages ?? [] : this.detachedMessages;
  }

  /** The transcript of one tab. */
  getTabMessages(tabId: string | null | undefined): ClaudeMessage[] {
    return this.findById(tabId)?.messages ?? [];
  }

  /**
   * Write the active tab's transcript.
   *
   * <p>The updater receives the tab's current transcript — the owner — and
   * returns the new one.</p>
   */
  updateActiveTabMessages(
    updater: (current: ClaudeMessage[]) => ClaudeMessage[],
  ): void {
    const activeId = this.activeTabId;
    if (!activeId) {
      this.detachedMessages = updater(this.detachedMessages);
      this.emit();
      return;
    }
    this.setTabs((prev) => prev.map((tab) => (
      tab.id === activeId
        ? { ...tab, messages: updater(tab.messages ?? []) }
        : tab
    )));
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
