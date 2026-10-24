import { act, renderHook } from '@testing-library/react';
import { useUsageTracking } from './useUsageTracking';

describe('useUsageTracking', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('initializes with daemon status unknown', () => {
    const { result } = renderHook(() => useUsageTracking());

    // 状态未知前：状态栏保持加载中。
    expect(result.current.daemonStatusLoaded).toBe(false);
    expect(result.current.isSdkInstalled('opencode')).toBe(false);
    expect(result.current.isSdkStatusKnown('opencode')).toBe(false);
  });

  it('keeps loading until opencode serve is ready', () => {
    const { result } = renderHook(() => useUsageTracking());

    // daemon 进程已起、但 serve 尚未就绪：状态栏保持加载中（daemonStatusLoaded=false）
    act(() => {
      window.dispatchEvent(
        new CustomEvent('updateDaemonStatus', {
          detail: JSON.stringify({ alive: true, serveReady: false }),
        })
      );
    });

    expect(result.current.daemonStatusLoaded).toBe(false);
    expect(result.current.isSdkInstalled('opencode')).toBe(true);
    expect(result.current.isSdkStatusKnown('opencode')).toBe(false);

    // serve 真正就绪后才隐藏状态栏、视为状态已知
    act(() => {
      window.dispatchEvent(
        new CustomEvent('updateDaemonStatus', {
          detail: JSON.stringify({ alive: true, serveReady: true }),
        })
      );
    });

    expect(result.current.daemonStatusLoaded).toBe(true);
    expect(result.current.isSdkInstalled('opencode')).toBe(true);
    expect(result.current.isSdkStatusKnown('opencode')).toBe(true);
  });

  it('unwraps the named daemon.status payload', () => {
    const { result } = renderHook(() => useUsageTracking());

    act(() => {
      window.dispatchEvent(
        new CustomEvent('updateDaemonStatus', {
          detail: {
            data: JSON.stringify({
              alive: true,
              serveReady: true,
              engine: { state: 'v2', locked: true },
            }),
          },
        })
      );
    });

    expect(result.current.daemonStatusLoaded).toBe(true);
    expect(result.current.isSdkInstalled('opencode')).toBe(true);
    expect(result.current.daemonEngine?.state).toBe('v2');
  });

  it('drains a named daemon.status payload buffered before mount', () => {
    (window as unknown as Record<string, unknown>).__pendingDaemonStatus = {
      data: JSON.stringify({ alive: true, serveReady: true }),
    };

    const { result } = renderHook(() => useUsageTracking());

    expect(result.current.daemonStatusLoaded).toBe(true);
    expect(result.current.isSdkInstalled('opencode')).toBe(true);
  });

  it('enters not-running state when daemon is not alive', () => {
    const { result } = renderHook(() => useUsageTracking());

    act(() => {
      window.dispatchEvent(
        new CustomEvent('updateDaemonStatus', {
          detail: JSON.stringify({ alive: false, serveReady: false }),
        })
      );
    });

    // daemon 未运行：状态栏切到「未运行」可重试态（daemonStatusLoaded=true，isSdkInstalled=false）
    expect(result.current.daemonStatusLoaded).toBe(true);
    expect(result.current.isSdkInstalled('opencode')).toBe(false);
    expect(result.current.isSdkStatusKnown('opencode')).toBe(true);
  });

  it('falls back to loaded on unparseable payload', () => {
    const { result } = renderHook(() => useUsageTracking());

    act(() => {
      window.dispatchEvent(
        new CustomEvent('updateDaemonStatus', { detail: 'not-json' })
      );
    });

    // 兜底：解析失败也视为状态已知，避免永久卡在 loading。
    expect(result.current.daemonStatusLoaded).toBe(true);
  });

  it('exposes the engine snapshot instead of discarding it', () => {
    const { result } = renderHook(() => useUsageTracking());

    act(() => {
      window.dispatchEvent(
        new CustomEvent('updateDaemonStatus', {
          detail: JSON.stringify({
            alive: true,
            serveReady: true,
            engine: {
              state: 'v2',
              locked: true,
              cliPath: 'C:\\tools\\opencode.cmd',
              cliSource: 'settings',
              cliVersion: 'opencode v2.0.27',
              probePath: '/api/info',
              httpStatus: 200,
              apiVersion: 2,
            },
          }),
        })
      );
    });

    expect(result.current.daemonEngine?.state).toBe('v2');
    expect(result.current.daemonEngine?.locked).toBe(true);
    // 版本号必须原样透传：它是排查「装了 v2 却按 v1 走」时的唯一现场证据。
    expect(result.current.daemonEngine?.cliVersion).toBe('opencode v2.0.27');
  });

  it('keeps unknown as a distinct state and warns about it', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { result } = renderHook(() => useUsageTracking());

    act(() => {
      window.dispatchEvent(
        new CustomEvent('updateDaemonStatus', {
          detail: JSON.stringify({
            alive: true,
            serveReady: false,
            engine: {
              state: 'unknown',
              locked: false,
              blocking: true,
              reasonKind: 'cli-not-found',
              reasonMessage: 'no opencode binary on any candidate path',
            },
          }),
        })
      );
    });

    // unknown 是一等状态：不能被折叠成 v1，也不能只在日志里静默。
    expect(result.current.daemonEngine?.state).toBe('unknown');
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('tolerates a payload without an engine field', () => {
    const { result } = renderHook(() => useUsageTracking());

    act(() => {
      window.dispatchEvent(
        new CustomEvent('updateDaemonStatus', {
          detail: JSON.stringify({ alive: true, serveReady: true }),
        })
      );
    });

    // 旧版宿主不推 engine；此时保持 null，而不是抛出或伪造一个状态。
    expect(result.current.daemonEngine).toBeNull();
  });
});
