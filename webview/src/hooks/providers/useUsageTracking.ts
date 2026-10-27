import { useCallback, useEffect, useState } from 'react';
import { cardDebugLog } from '../../utils/bridge';
import { sendWindowCommand } from '../../utils/bridge';
import { registerHostEvents, unregisterHostEvents } from '../../utils/hostEventRegistry';

/**
 * The engine resolution snapshot the daemon reports (see `daemon.js` →
 * `engine_status`, mirrored into the daemon status payload by
 * `DaemonStatusHandler#pushStatus`).
 *
 * `state` is deliberately a three-way union rather than a boolean: `unknown`
 * means "resolution never completed", which is a different situation from
 * "the engine is v1" and must never be collapsed into it — that conflation is
 * exactly what turned the 2026-10-16 probe failure into silent v1 routing
 * against a v2-only server.
 */
export interface DaemonEngineStatus {
  state?: 'v1' | 'v2' | 'unknown' | string;
  locked?: boolean;
  blocking?: boolean;
  cliPath?: string | null;
  cliSource?: string | null;
  cliVersion?: string | null;
  probePath?: string | null;
  httpStatus?: number | string | null;
  apiVersion?: number | string | null;
  reasonKind?: string | null;
  reasonMessage?: string | null;
}

/**
 * Usage % / token counters and daemon alive status.
 * `isSdkInstalled(providerId)` now simply checks if the opencode daemon is alive.
 */
export function useUsageTracking() {
  const [usagePercentage, setUsagePercentage] = useState(0);
  const [usageUsedTokens, setUsageUsedTokens] = useState<number | undefined>(undefined);
  const [usageMaxTokens, setUsageMaxTokens] = useState<number | undefined>(undefined);
  const [daemonAlive, setDaemonAlive] = useState(false);
  const [daemonStatusLoaded, setDaemonStatusLoaded] = useState(false);
  const [daemonEngine, setDaemonEngine] = useState<DaemonEngineStatus | null>(null);

  useEffect(() => {
    const applyStatus = (raw: unknown) => {
      try {
        let data: any = raw;
        if (typeof data === 'string') {
          data = JSON.parse(data);
        }
        // Named events wrap the JSON under `data`; older callbacks passed the
        // JSON string directly. Unwrap either shape before reading serveReady.
        if (data && typeof data === 'object' && 'data' in data) {
          const inner = (data as { data?: unknown }).data;
          if (typeof inner === 'string') {
            data = JSON.parse(inner);
          } else if (inner && typeof inner === 'object') {
            data = inner;
          }
        }
        cardDebugLog('[Probe] daemon.status handler fired:', JSON.stringify(data));
        setDaemonAlive(!!data.alive);
        if (data.engine && typeof data.engine === 'object') {
          setDaemonEngine(data.engine as DaemonEngineStatus);
          // Surface the two states that used to be invisible: an engine that is
          // still unresolved, and one that failed for a reason the user can act
          // on (bad settings path, CLI not found, probe mismatch). Without this
          // the only symptom is "serve not ready", which reads as "still
          // starting" even when it will never become ready.
          if (data.engine.state === 'unknown' || data.engine.blocking) {
            console.warn(
              '[Engine] opencode engine not usable:',
              data.engine.state,
              data.engine.reasonKind || '',
              data.engine.reasonMessage || '',
            );
          }
        }
        // 状态栏（"正在检查 opencode serve 状态..."）必须等到 serve 真正就绪
        // （serveReady=true）才消失；serve 进程都没运行（alive=false）则立即进入
        // 「未运行」可重试态；alice 为真但 serve 尚未就绪时保持 loading 转圈。
        if (data.alive === false || data.serveReady === true) {
          setDaemonStatusLoaded(true);
        }
        // 其余情况（alive=true && serveReady=false/缺失）保持 daemonStatusLoaded=false，
        // 状态栏继续显示加载中，直到宿主发来 serveReady:true。
      } catch {
        // 兜底：解析失败也视为状态已知，避免永久卡在 loading。
        setDaemonStatusLoaded(true);
      }
    };

    // The host event is registered here and nowhere else: one owner means one
    // payload path. An event that arrives before this effect runs waits in the
    // dispatcher queue instead of in a window slot.
    registerHostEvents({ 'daemon.status': (payload: unknown) => applyStatus(payload) });
    cardDebugLog('[Probe] daemon.status handler registered');

    return () => {
      unregisterHostEvents(['daemon.status']);
    };
  }, []);

  const isSdkInstalled = useCallback(
    (_providerId: string): boolean => {
      return daemonAlive;
    },
    [daemonAlive],
  );

  const isSdkStatusKnown = useCallback((_providerId: string): boolean => {
    return daemonStatusLoaded;
  }, [daemonStatusLoaded]);

  const retryDaemonStatus = useCallback(() => {
    setDaemonStatusLoaded(false);
    if (window.sendToJava) {
      sendWindowCommand('check_daemon_status');
    }
  }, []);

  return {
    usagePercentage,
    setUsagePercentage,
    usageUsedTokens,
    setUsageUsedTokens,
    usageMaxTokens,
    setUsageMaxTokens,
    daemonStatusLoaded,
    daemonEngine,
    retryDaemonStatus,
    isSdkInstalled,
    isSdkStatusKnown,
  };
}

export type UseUsageTrackingReturn = ReturnType<typeof useUsageTracking>;
