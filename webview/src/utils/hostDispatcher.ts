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

import { handlerForSemanticType } from './semanticEvents';

interface PendingHostEvent {
  type: string;
  args: unknown[];
  queuedAt: number;
}

interface HostEnvelope {
  v?: number;
  type?: string;
  tabId?: string;
  epoch?: number;
  payload?: { args?: unknown[] } | unknown[];
}

const MAX_PENDING_HOST_EVENTS = 2_000;
const MAX_QUEUE_AGE_MS = 30_000;
const FLUSH_INTERVAL_MS = 50;

const pending: PendingHostEvent[] = [];
const lastEpochByTab = new Map<string, number>();
let flushTimer: number | null = null;

function resolveWindowFunction(path: string): ((...args: unknown[]) => void) | null {
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

function resolveHandler(type: string): ((...args: unknown[]) => void) | null {
  if (!type) return null;
  // A semantic event name (content.delta, message.snapshot, …) is the
  // protocol; the catalog says which handler answers it.
  const semantic = handlerForSemanticType(type);
  if (semantic) {
    const handler = resolveWindowFunction(semantic);
    if (handler) return handler;
  }
  // Fallback: the legacy function name, which is also what the host sends
  // next to the semantic type.
  return resolveWindowFunction(type.startsWith('window.') ? type.slice('window.'.length) : type);
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

/**
 * Apply one typed host envelope.
 *
 * `tabId` + `epoch` let the dispatcher reject an event produced for an older
 * binding of the same tab (a late snapshot from before a rebind must not
 * repaint the new session). Events without an epoch keep legacy semantics.
 */
export function dispatchHostEnvelope(envelope: HostEnvelope): void {
  if (!envelope || typeof envelope.type !== 'string' || !envelope.type) return;

  if (envelope.tabId) {
    const epoch = typeof envelope.epoch === 'number' ? envelope.epoch : 0;
    const last = lastEpochByTab.get(envelope.tabId) ?? -1;
    if (epoch > 0 && epoch < last) {
      return;
    }
    if (epoch > last) {
      lastEpochByTab.set(envelope.tabId, epoch);
    }
  }

  const payload = envelope.payload;
  let args: unknown[] = [];
  if (Array.isArray(payload)) {
    args = payload;
  } else if (payload && typeof payload === 'object') {
    const candidate = (payload as { args?: unknown }).args;
    if (Array.isArray(candidate)) args = candidate;
  }
  dispatchHostEvent(envelope.type, args);
}

export function dispatchHostEnvelopeBatch(batch: unknown): void {
  if (!Array.isArray(batch)) return;
  for (const envelope of batch) {
    try {
      dispatchHostEnvelope(envelope as HostEnvelope);
    } catch (error) {
      console.error('[HostDispatcher] envelope failed:', error);
    }
  }
}

/** Test hook: forget per-tab epochs. */
export function resetHostDispatcherEpochs(): void {
  lastEpochByTab.clear();
}

/**
 * Trust the host's current epoch for a tab. Used after a reload resync so a
 * delayed event from before the reload is rejected instead of repainting.
 */
export function observeHostEpoch(tabId: string, epoch: number): void {
  if (!tabId || !Number.isFinite(epoch) || epoch <= 0) return;
  const last = lastEpochByTab.get(tabId) ?? -1;
  if (epoch > last) {
    lastEpochByTab.set(tabId, epoch);
  }
}

declare global {
  interface Window {
    __opencodeDispatch?: (type: string, args?: unknown[]) => void;
    __opencodeDispatchBatch?: (batch: unknown) => void;
    __flushOpencodeDispatch?: () => void;
  }
}

window.__opencodeDispatch = dispatchHostEvent;
window.__opencodeDispatchBatch = dispatchHostEnvelopeBatch;
window.__flushOpencodeDispatch = flushHostDispatcher;
