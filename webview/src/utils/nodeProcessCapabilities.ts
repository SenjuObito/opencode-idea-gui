/**
 * Node process capabilities subscriber registry.
 *
 * Mirrors runtimeProviderCapabilities.ts — installs single dispatchers in the
 * host event registry and routes incoming JSON payloads to subscribed listeners.
 *
 * Multiple consumers (e.g. the settings menu badge and the panel itself) can
 * subscribe without overwriting each other's callbacks.
 */

import { sendWindowCommand } from './bridge';
import { hostEventHandler, registerHostEvents } from './hostEventRegistry';

export type NodeProcessKind = 'DAEMON' | 'CHANNEL' | 'ORPHAN';

export interface NodeProcessInfo {
  id: string;
  kind: NodeProcessKind;
  provider?: string;
  pid: number;
  alive: boolean;
  startedAt: number;
  uptimeMs: number;
  command?: string;
  heapUsed?: number;
  activeRequestCount: number;
  channelId?: string;
  sessionId?: string;
  tabName?: string;
  orphan: boolean;
}

export interface NodeProcessTotals {
  daemon: number;
  channel: number;
  orphan: number;
  all: number;
}

export interface NodeProcessSnapshot {
  snapshotAt: number;
  totals: NodeProcessTotals;
  processes: NodeProcessInfo[];
}

export interface NodeProcessKillResult {
  pid?: number;
  id?: string;
  success?: boolean;
  error?: string;
  killed?: number;
  restart?: boolean;
}

type SnapshotListener = (snapshot: NodeProcessSnapshot) => void;
type KillResultListener = (result: NodeProcessKillResult) => void;

const snapshotListeners = new Set<SnapshotListener>();
const killResultListeners = new Set<KillResultListener>();

function emit<T>(listeners: Set<(value: T) => void>, value: T): void {
  Array.from(listeners).forEach((listener) => {
    try {
      listener(value);
    } catch (error) {
      console.error('[nodeProcessCapabilities] Listener threw:', error);
    }
  });
}

function safeParseSnapshot(json: string): NodeProcessSnapshot | null {
  try {
    const parsed = JSON.parse(json) as NodeProcessSnapshot;
    if (!parsed || !Array.isArray(parsed.processes)) {
      return null;
    }
    return parsed;
  } catch (error) {
    console.error('[nodeProcessCapabilities] Failed to parse snapshot:', error);
    return null;
  }
}

function safeParseKillResult(json: string): NodeProcessKillResult | null {
  try {
    return JSON.parse(json) as NodeProcessKillResult;
  } catch (error) {
    console.error('[nodeProcessCapabilities] Failed to parse kill result:', error);
    return null;
  }
}

export function installNodeProcessDispatchers(): void {
  registerHostEvents({
    'node.processes': (json: string) => {
      const snapshot = safeParseSnapshot(json);
      if (snapshot) emit(snapshotListeners, snapshot);
    },
    'node.process.killed': (json: string) => {
      const result = safeParseKillResult(json);
      if (result) emit(killResultListeners, result);
    },
  });
}

function ensureInstalled(): void {
  if (typeof window === 'undefined') return;
  if (hostEventHandler('node.processes') && hostEventHandler('node.process.killed')) return;
  installNodeProcessDispatchers();
}

export function subscribeNodeProcesses(listener: SnapshotListener): () => void {
  ensureInstalled();
  snapshotListeners.add(listener);
  return () => {
    snapshotListeners.delete(listener);
  };
}

export function subscribeNodeProcessKillResult(listener: KillResultListener): () => void {
  ensureInstalled();
  killResultListeners.add(listener);
  return () => {
    killResultListeners.delete(listener);
  };
}

/** Request the latest snapshot from Java. The response arrives via `window.updateNodeProcesses`. */
export function fetchNodeProcesses(): void {
  sendWindowCommand('diagnostics.nodeProcesses.list');
}

/** Ask the backend to kill a single process by PID. */
export function killNodeProcess(pid: number, id?: string): void {
  sendWindowCommand('diagnostics.nodeProcesses.kill', id ? { pid, id } : { pid });
}

/** Ask the backend to kill every detected orphan process. */
export function killAllOrphanProcesses(): void {
  sendWindowCommand('diagnostics.nodeProcesses.killOrphans');
}

/** Ask the backend to restart the daemon owning the given PID (falls back to plain kill on miss). */
export function restartNodeDaemon(pid: number): void {
  sendWindowCommand('diagnostics.nodeProcesses.restart', { pid });
}
