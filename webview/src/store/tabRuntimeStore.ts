import type { StreamingRuntimeSnapshot } from '../utils/streamingRuntime';

/**
 * Per-tab streaming runtime snapshots.
 *
 * The transport still uses one set of refs, but ownership of the saved state is
 * explicit and keyed by tab id. A removed tab must drop its snapshot so a later
 * tab that reuses the id can never inherit a dead turn's accumulator.
 */
export class TabRuntimeStore {
  private readonly snapshots = new Map<string, StreamingRuntimeSnapshot>();

  get(tabId: string): StreamingRuntimeSnapshot | undefined {
    return this.snapshots.get(tabId);
  }

  set(tabId: string, snapshot: StreamingRuntimeSnapshot): void {
    this.snapshots.set(tabId, snapshot);
  }

  delete(tabId: string): void {
    this.snapshots.delete(tabId);
  }

  clear(): void {
    this.snapshots.clear();
  }

  size(): number {
    return this.snapshots.size;
  }
}
