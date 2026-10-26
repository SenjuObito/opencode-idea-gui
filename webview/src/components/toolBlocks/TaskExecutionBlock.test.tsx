import { act, fireEvent, render } from '@testing-library/react';
import TaskExecutionBlock from './TaskExecutionBlock';

const mockSendSessionCommand = vi.fn();
let mockHistories: Record<string, unknown> = {};
const mockUseSessionId = vi.fn<() => string | null>();
const mockUseSessionProvider = vi.fn<() => string>();
const mockGetToolResultRaw = vi.fn<(toolUseId: string) => Record<string, unknown> | null>();
const mockUseTaskEvent = vi.fn<(toolUseId: string | undefined) => unknown>();

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

vi.mock('../../utils/bridge', () => ({
  sendSessionCommand: (...args: unknown[]) => mockSendSessionCommand(...args),
}));

vi.mock('../../contexts/SubagentContext', () => ({
  useSubagentHistories: () => mockHistories,
  useSessionId: () => mockUseSessionId(),
  useSessionProvider: () => mockUseSessionProvider(),
  useGetToolResultRaw: () => mockGetToolResultRaw,
  useTaskEvent: (toolUseId: string | undefined) => mockUseTaskEvent(toolUseId),
}));

describe('TaskExecutionBlock transcript loading', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockSendSessionCommand.mockReset();
    mockGetToolResultRaw.mockReset();
    mockUseSessionId.mockReset();
    mockUseSessionProvider.mockReset();
    mockUseTaskEvent.mockReset();

    mockHistories = {};
    mockGetToolResultRaw.mockReturnValue(null);
    mockUseSessionId.mockReturnValue('session-1');
    mockUseSessionProvider.mockReturnValue('claude');
    mockUseTaskEvent.mockReturnValue(undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('requests the transcript once on expand and never starts an interval', () => {
    const setIntervalSpy = vi.spyOn(window, 'setInterval');

    const { container } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="task-1"
        isStreaming={false}
        input={{
          description: 'Inspect render path',
          subagent_type: 'Explore',
          run_in_background: true,
        }}
      />,
    );

    expect(mockSendSessionCommand).not.toHaveBeenCalled();
    fireEvent.click(container.querySelector('.task-header') as HTMLElement);

    expect(mockSendSessionCommand).toHaveBeenCalledTimes(1);
    expect(mockSendSessionCommand).toHaveBeenCalledWith(
      'subagent.history',
      'session-1',
      expect.objectContaining({ toolUseId: 'task-1' }),
    );
    expect(setIntervalSpy).not.toHaveBeenCalled();
  });

  it('expands the task details when the header is clicked', () => {
    const { container } = render(
      <TaskExecutionBlock
        name="Task"
        toolId="task-1"
        input={{
          description: 'Inspect render path',
          subagent_type: 'Explore',
        }}
      />,
    );

    expect(container.querySelector('.task-details')).toBeNull();

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);

    expect(container.querySelector('.task-details')).toBeTruthy();
  });

  it('does not refetch the transcript on a timer', () => {
    const { container } = render(
      <TaskExecutionBlock
        name="Task"
        toolId="task-1"
        isStreaming={true}
        input={{
          description: 'Inspect render path',
          subagent_type: 'Explore',
        }}
      />,
    );

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);
    expect(mockSendSessionCommand).toHaveBeenCalledTimes(1);

    mockSendSessionCommand.mockClear();
    act(() => {
      vi.advanceTimersByTime(10_000);
    });

    expect(mockSendSessionCommand).not.toHaveBeenCalled();
  });

  it('keeps an async agent pending until its task_notification lands', () => {
    mockUseTaskEvent.mockReturnValue(undefined);

    const { container } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-async"
        isStreaming={true}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        } as any}
      />,
    );

    // Launch ack tool_result is present, but async agents must NOT flip to
    // completed on it alone.
    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('pending');
    expect(indicator?.className).not.toContain('completed');
  });

  it('keeps an unfinished async agent pending after history reload', () => {
    mockUseTaskEvent.mockReturnValue(undefined);
    mockHistories = { 'agent-async': { success: true, completed: false, messages: [] } };

    const { container } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-async"
        isStreaming={false}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        } as any}
      />,
    );

    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('pending');
    expect(indicator?.className).not.toContain('completed');
  });

  it('does not complete an async agent from a terminal sidechain transcript', () => {
    // A transcript is detail-only now: only core's `subagent.status` (or the
    // legacy task_notification, same store) can complete a background launch.
    mockUseTaskEvent.mockReturnValue(undefined);
    mockHistories = { 'agent-async': { success: true, status: 'completed', completed: true, messages: [] } };

    const { container } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-async"
        isStreaming={false}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        } as any}
      />,
    );

    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('pending');
    expect(indicator?.className).not.toContain('completed');
  });

  it('keeps an async launch pending regardless of its cached transcript', () => {
    mockHistories = {
      'subagent-1': { success: true, status: 'completed', completed: true, messages: [] },
    };

    const { container } = render(
      <TaskExecutionBlock
        name="subagent"
        toolId="subagent-1"
        isStreaming={false}
        result={{
          type: 'tool_result',
          tool_use_id: 'subagent-1',
          content: '<subagent sessionID="ses_child" state="running" description="Explore repo">…</subagent>',
        } as any}
        input={{ description: 'Explore repo', agent: 'explore' } as any}
      />,
    );

    // The transcript is detail-only. A background launch stays running until
    // core's `subagent.status` event arrives, whatever an expand-time snapshot
    // happened to say.
    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('pending');
    expect(indicator?.className).not.toContain('completed');
  });

  it('completes an inline agent even when a cached transcript still says running', () => {
    // Regression: the user expanded the row while the child was still running,
    // so the cached transcript says `running`. The child then finished and its
    // terminal report arrived as the parent tool_result. Polling is gone, so the
    // stale snapshot must not pin the spinner — and it is not a status input at
    // all any more.
    mockHistories = {
      'subagent-1': { success: true, status: 'running', completed: false, messages: [] },
    };

    const { container } = render(
      <TaskExecutionBlock
        name="subagent"
        toolId="subagent-1"
        isStreaming={false}
        result={{
          type: 'tool_result',
          tool_use_id: 'subagent-1',
          content: '<subagent sessionID="ses_child" state="completed">done</subagent>',
        } as any}
        input={{ description: 'Explore repo', agent: 'explore' } as any}
      />,
    );

    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('completed');
    expect(indicator?.className).not.toContain('pending');
  });

  it('requests the transcript once per expansion and never uses it for status', () => {
    const input = { description: 'Explore repo', agent: 'explore' } as any;
    const result = {
      type: 'tool_result',
      tool_use_id: 'subagent-1',
      content: '<subagent sessionID="ses_child" state="completed">done</subagent>',
    } as any;

    const { container, rerender } = render(
      <TaskExecutionBlock name="subagent" toolId="subagent-1" isStreaming={true} input={input} />,
    );
    fireEvent.click(container.querySelector('.task-header') as HTMLElement);
    expect(mockSendSessionCommand).toHaveBeenCalledTimes(1);

    // The host answers with the child transcript snapshot taken while it was
    // still running. That snapshot alone must not trigger another request.
    mockHistories = {
      'subagent-1': { success: true, status: 'running', completed: false, messages: [] },
    };
    mockSendSessionCommand.mockClear();
    rerender(
      <TaskExecutionBlock name="subagent" toolId="subagent-1" isStreaming={true} input={input} />,
    );
    expect(mockSendSessionCommand).not.toHaveBeenCalled();

    // The child's terminal report arrives as the parent tool_result; status
    // comes from that (or core's event), so the transcript is not re-read.
    rerender(
      <TaskExecutionBlock name="subagent" toolId="subagent-1" isStreaming={true} input={input} result={result} />,
    );
    expect(mockSendSessionCommand).not.toHaveBeenCalled();
    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('completed');

    // Unrelated re-renders stay quiet too.
    rerender(
      <TaskExecutionBlock name="subagent" toolId="subagent-1" isStreaming={true} input={input} result={result} />,
    );
    expect(mockSendSessionCommand).not.toHaveBeenCalled();
  });

  it('flips an async agent to completed when a task_notification arrives', () => {
    mockUseTaskEvent.mockReturnValue({
      toolUseId: 'agent-async',
      status: 'completed',
    } as any);

    const { container } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-async"
        isStreaming={true}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        } as any}
      />,
    );

    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('completed');
  });

  it('shows the error indicator when a task_notification reports failure', () => {
    mockUseTaskEvent.mockReturnValue({
      toolUseId: 'agent-fail',
      status: 'failed',
    } as any);

    const { container } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-fail"
        isStreaming={true}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        } as any}
      />,
    );

    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('error');
  });

  it('marks an async agent as errored when the launch tool_result reports is_error', () => {
    // A failed launch (validation error before the background task was
    // registered) returns an is_error tool_result and never emits a
    // task_notification - the card must show error, not stay "pending".
    mockUseTaskEvent.mockReturnValue(undefined);

    const { container } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-launch-fail"
        isStreaming={true}
        result={{ type: 'tool_result', tool_use_id: 'agent-launch-fail', content: 'Agent Teams is not available', is_error: true } as any}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        } as any}
      />,
    );

    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('error');
    expect(indicator?.className).not.toContain('pending');
  });

  it('a pushed failure flips the card without another request', () => {
    mockUseTaskEvent.mockReturnValue(undefined);

    const { container, rerender } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-push-fail"
        isStreaming={true}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        }}
      />,
    );

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);
    expect(mockSendSessionCommand).toHaveBeenCalledTimes(1);

    // core pushes the terminal state; no transcript roundtrip is involved.
    mockUseTaskEvent.mockReturnValue({
      toolUseId: 'agent-push-fail',
      status: 'failed',
    } as any);

    mockSendSessionCommand.mockClear();
    rerender(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-push-fail"
        isStreaming={true}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        }}
      />,
    );

    const indicator = container.querySelector('.tool-status-indicator');
    expect(indicator?.className).toContain('error');
    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    expect(mockSendSessionCommand).not.toHaveBeenCalled();
  });

  it('renders task_event usage in SubagentProcessDetails for a completed async agent', () => {
    mockUseTaskEvent.mockReturnValue({
      toolUseId: 'agent-usage',
      status: 'completed',
      totalTokens: 4200,
      totalToolUseCount: 7,
      totalDurationMs: 18000,
      summary: '调研完成',
    } as any);

    const { container } = render(
      <TaskExecutionBlock
        name="Agent"
        toolId="agent-usage"
        isStreaming={false}
        input={{
          description: 'background research',
          subagent_type: 'research',
          run_in_background: true,
        } as any}
      />,
    );

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);

    const stats = container.querySelector('.subagent-process-stats');
    expect(stats).toBeTruthy();
    expect(stats?.textContent).toContain('7');
    expect(stats?.textContent).toContain('4,200');
    expect(container.querySelector('.subagent-result-card')?.textContent).toContain('调研完成');
  });

  it('parses spawn_agent meta (nickname, model, reasoning effort) from result text', () => {
    mockUseTaskEvent.mockReturnValue(undefined);
    mockGetToolResultRaw.mockReturnValue(null);

    const result = {
      type: 'tool_result',
      tool_use_id: 'spawn-1',
      content: '{"agent_id":"af5a83aa","nickname":"researcher","model":"claude-sonnet-4-6","reasoning_effort":"high"}',
    } as any;

    const { container } = render(
      <TaskExecutionBlock
        name="spawn_agent"
        toolId="spawn-1"
        result={result}
        input={{ prompt: 'do research' } as any}
      />,
    );

    const summaries = container.querySelectorAll('.tool-title-summary');
    const text = Array.from(summaries).map((el) => el.textContent).join(' ');
    expect(text).toContain('researcher');
    expect(text).toContain('claude-sonnet-4-6');
    expect(text).toContain('high');
  });

  it('loads Codex spawn_agent history with provider and task path', () => {
    mockUseSessionProvider.mockReturnValue('codex');
    const result = {
      type: 'tool_result',
      tool_use_id: 'call-1',
      content: '{"task_name":"/root/reviewer"}',
    } as any;

    const { container } = render(
      <TaskExecutionBlock
        name="spawn_agent"
        toolId="call-1"
        result={result}
        input={{ prompt: 'review' } as any}
      />,
    );

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);

    expect(mockSendSessionCommand).toHaveBeenCalledWith(
      'subagent.history',
      'session-1',
      {
        sessionId: 'session-1',
        provider: 'codex',
        agentId: undefined,
        agentPath: '/root/reviewer',
        description: undefined,
        toolUseId: 'call-1',
      },
    );
    expect(container.querySelector('.tool-status-indicator')?.className).toContain('pending');
  });
});
