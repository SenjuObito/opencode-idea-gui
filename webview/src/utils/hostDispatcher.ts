/**
 * Unified host -> webview event dispatcher.
 *
 * The Java bridge used to invoke `window.<fn>` directly. That works only when
 * the callback is already registered; during app boot (or after a reload) the
 * first host events arrived before React mounted and were silently dropped.
 *
 * Every host event now goes through this single entry point. Unknown events are
 * queued until their handler appears, then flushed in arrival order. The direct
 * call remains the fallback for older hosts and for raw scripts.
 */

interface PendingHostEvent {
  type: string;
  args: unknown[];
  queuedAt: number;
}

const MAX_PENDING_HOST_EVENTS = 2_000;
const MAX_QUEUE_AGE_MS = 30_000;
const FLUSH_INTERVAL_MS = 50;

const pending: PendingHostEvent[] = [];
let flushTimer: number | null = null;

function resolveHandler(type: string): ((...args: unknown[]) => void) | null {
  const path = type.startsWith('window.') ? type.slice('window.'.length) : type;
  if (!path) return null;
  let target: unknown = window;
  for (const part of path.split('.')) {
    if (!part) return null;
    if (target == null) return null;
    target = (target as Record<string, unknown>)[part];
  }
  return typeof target === 'function'
    ? (target as (...args: unknown[]) => void)
    : null;
}

function scheduleFlush(): void {
  if (typeof window === 'undefined' || flushTimer !== null) return;
  flushTimer = window.setTimeout(() => {
    flushTimer = null;
    flushHostDispatcher();
  }, FLUSH_INTERVAL_MS);
}

export function flushHostDispatcher(): void {
  if (typeof window === 'undefined') {
    pending.length = 0;
    return;
  }
  const now = Date.now();
  for (let i = 0; i < pending.length;) {
    const event = pending[i];
    if (now - event.queuedAt > MAX_QUEUE_AGE_MS) {
      pending.splice(i, 1);
      continue;
    }
    const handler = resolveHandler(event.type);
    if (!handler) {
      i += 1;
      continue;
    }
    pending.splice(i, 1);
    try {
      handler.apply(window, event.args);
    } catch (error) {
      console.error(`[HostDispatcher] ${event.type} handler error:`, error);
    }
  }
  if (pending.length > 0) {
    scheduleFlush();
  }
}

export function dispatchHostEvent(type: string, args: unknown[] = []): void {
  if (!type || typeof window === 'undefined') return;
  const handler = resolveHandler(type);
  if (handler) {
    try {
      handler.apply(window, args);
    } catch (error) {
      console.error(`[HostDispatcher] ${type} handler error:`, error);
    }
    return;
  }
  if (pending.length >= MAX_PENDING_HOST_EVENTS) {
    pending.shift();
  }
  pending.push({ type, args, queuedAt: Date.now() });
  scheduleFlush();
}

export function pendingHostEventCount(): number {
  return pending.length;
}

declare global {
  interface Window {
    __opencodeDispatch?: (type: string, args?: unknown[]) => void;
    __flushOpencodeDispatch?: () => void;
  }
}

window.__opencodeDispatch = dispatchHostEvent;
window.__flushOpencodeDispatch = flushHostDispatcher;
