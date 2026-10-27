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

/**
 * The runtime state of a window that has no tab yet.
 *
 * <p>These fields are part of {@link SessionTabData} because a tab owns its
 * conversation and the turn state that produced it. The view reads them from
 * the active tab; background events write them on the owning tab. There is no
 * window-wide copy that a tab switch would have to move.</p>
 */
const createDetachedTab = (): SessionTabData => ({
  id: '',
  sessionId: null,
  title: '',
  messages: [],
  loading: false,
  streamingActive: false,
  isThinking: false,
  status: undefined,
  turnStartedAt: null,
  isCompacting: false,
  compactingStartTime: null,
  sseTodos: null,
  subagentHistories: {},
});

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
  /**
   * The state of a window that has no tab yet.
   *
   * <p>Before the first tab is created (and in focused tests) the active tab is
   * this object. It owns the same runtime fields as a real tab, so setters keep
   * one code path instead of maintaining a second, window-wide copy.</p>
   */
  private detachedTab: SessionTabData = createDetachedTab();
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
   * The tab that is on screen, or the detached state before the first tab.
   *
   * <p>The returned object is the owner of the active transcript and runtime
   * state. Callers must not mutate it; `updateActiveTab` replaces it.</p>
   */
  readonly getActiveTab = (): SessionTabData => (
    this.findById(this.activeTabId) ?? this.detachedTab
  );

  /**
   * Replace the active tab with the result of `updater`.
   *
   * <p>Every runtime setter goes through here, so the active tab remains the
   * only owner of `loading`, `streamingActive`, `isThinking` and the related
   * turn state. A background event writes its own tab through `updateTab`.</p>
   */
  readonly updateActiveTab = (updater: (tab: SessionTabData) => SessionTabData): void => {
    const activeId = this.activeTabId;
    if (!activeId) {
      const next = updater(this.detachedTab);
      if (next === this.detachedTab) return;
      this.detachedTab = next;
      this.emit();
      return;
    }
    this.setTabs((prev) => prev.map((tab) => (
      tab.id === activeId ? updater(tab) : tab
    )));
  };

  /**
   * The transcript of the tab that is on screen.
   *
   * <p>The store owns it: the view reads it from here, so switching tabs
   * never has to copy a transcript into another piece of state.</p>
   */
  getActiveTabMessages(): ClaudeMessage[] {
    return this.getActiveTab().messages ?? [];
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
    this.updateActiveTab((tab) => ({
      ...tab,
      messages: updater(tab.messages ?? []),
    }));
  }

  findIdBySession(sessionId: string | null | undefined): string | null {
    if (!sessionId) return null;
    return this.tabs.find(
      (tab) => tab.sessionId === sessionId || tab.id === sessionId,
    )?.id ?? null;
  }

  findTabBySessionId(sessionId: string | null | undefined): SessionTabData | undefined {
    if (!sessionId) return undefined;
    return this.tabs.find((tab) => tab.sessionId === sessionId || tab.id === sessionId);
  }

  getActiveSessionId(): string | null {
    const active = this.getActiveTab();
    return active?.sessionId ?? (active?.id || null);
  }

  updateTab(
    tabId: string | null | undefined,
    updater: (tab: SessionTabData) => SessionTabData,
  ): boolean {
    if (!tabId) return false;
    let found = false;
    this.setTabs((prev) =>
      prev.map((tab) => {
        if (tab.id === tabId) {
          found = true;
          return updater(tab);
        }
        return tab;
      }),
    );
    return found;
  }

  updateBackgroundTab(
    sessionId: string | null | undefined,
    updater: (tab: SessionTabData) => SessionTabData,
  ): boolean {
    const tabId = this.findIdBySession(sessionId);
    if (!tabId) return false;
    return this.updateTab(tabId, updater);
  }

  isActiveTab(tabId: string | null | undefined): boolean {
    if (!tabId) return false;
    return this.activeTabId === tabId;
  }

  isActiveSession(sessionId: string | null | undefined): boolean {
    if (!sessionId) return false;
    const active = this.getActiveTab();
    if (!active) return false;
    return active.id === sessionId || active.sessionId === sessionId;
  }

  private emit(): void {
    for (const listener of this.listeners) {
      listener();
    }
  }
}
