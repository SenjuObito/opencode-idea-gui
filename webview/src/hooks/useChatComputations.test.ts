import { describe, expect, it } from 'vitest';
import type { ClaudeContentBlock, ClaudeMessage } from '../types';
import { sliceLatestConversationTurn } from '../utils/turnScope';
import { deriveTodosForTurn } from './useChatComputations';

interface TestMessage extends ClaudeMessage {
  __blocks?: ClaudeContentBlock[];
}

const getContentBlocks = (message: ClaudeMessage): ClaudeContentBlock[] =>
  (message as TestMessage).__blocks ?? [];

const user = (content: string): ClaudeMessage => ({ type: 'user', content });

const assistant = (blocks: ClaudeContentBlock[]): ClaudeMessage =>
  ({ type: 'assistant', __blocks: blocks }) as TestMessage;

const toolUse = (id: string, name: string, input: Record<string, unknown>): ClaudeContentBlock =>
  ({ type: 'tool_use', id, name, input });

describe('deriveTodosForTurn', () => {
  it('does not carry a completed plan into a new user turn', () => {
    const messages = [
      user('previous request'),
      assistant([
        toolUse('plan-1', 'update_plan', {
          plan: Array.from({ length: 5 }, (_, index) => ({
            step: `Previous step ${index + 1}`,
            status: 'completed',
          })),
        }),
      ]),
      user('Only answer OK'),
    ];

    const latestTurn = sliceLatestConversationTurn(messages);
    expect(deriveTodosForTurn(latestTurn, getContentBlocks, true)).toEqual([]);
  });

  it('shows the latest plan created in the current turn', () => {
    const messages = [
      user('previous request'),
      assistant([toolUse('old-plan', 'update_plan', {
        plan: [{ step: 'Old step', status: 'completed' }],
      })]),
      user('new request'),
      assistant([toolUse('new-plan', 'update_plan', {
        plan: [
          { step: 'First', status: 'in_progress' },
          { step: 'Second', status: 'pending' },
          { step: 'Third', status: 'pending' },
        ],
      })]),
    ];

    const latestTurn = sliceLatestConversationTurn(messages);
    expect(deriveTodosForTurn(latestTurn, getContentBlocks, true)).toEqual([
      { content: 'First', status: 'in_progress' },
      { content: 'Second', status: 'pending' },
      { content: 'Third', status: 'pending' },
    ]);
  });

  it('does not carry completed structured tasks into a new user turn', () => {
    const messages = [
      user('previous request'),
      assistant([toolUse('task-create-1', 'TaskCreate', { subject: 'Previous task' })]),
      {
        type: 'user',
        raw: {
          content: [{
            type: 'tool_result',
            tool_use_id: 'task-create-1',
            content: 'Task #1 created successfully',
          }],
        },
      } as ClaudeMessage,
      assistant([toolUse('task-update-1', 'TaskUpdate', { taskId: '1', status: 'completed' })]),
      user('Only answer OK'),
    ];

    const latestTurn = sliceLatestConversationTurn(messages);
    expect(deriveTodosForTurn(latestTurn, getContentBlocks, true)).toEqual([]);
  });

  it('keeps earlier todos when the full transcript is scoped (settled history replay)', () => {
    // Non-streaming scope feeds the WHOLE transcript to deriveTodosForTurn, so an
    // earlier turn's plan survives even when the last turn has no task tool —
    // exactly what a resumed history session needs to render its task list.
    const messages = [
      user('previous request'),
      assistant([toolUse('old-plan', 'update_plan', {
        plan: [
          { step: 'Kept step', status: 'completed' },
          { step: 'In-flight step', status: 'in_progress' },
        ],
      })]),
      user('Only answer OK'),
    ];

    expect(deriveTodosForTurn(messages, getContentBlocks, false)).toEqual([
      { content: 'Kept step', status: 'completed' },
      { content: 'In-flight step', status: 'in_progress' },
    ]);
  });
});

describe('useChatComputations findToolResult', () => {
  it('finds tool result even when message raw is a serialized JSON string', async () => {
    const { renderHook } = await import('@testing-library/react');
    const { useChatComputations } = await import('./useChatComputations');

    const messages: ClaudeMessage[] = [
      {
        type: 'assistant',
        content: '',
        raw: {
          message: {
            content: [{ type: 'tool_use', id: 'tool-json-str', name: 'bash', input: { command: 'ls' } }],
          },
        },
      },
      {
        type: 'user',
        content: '[tool_result]',
        raw: JSON.stringify({
          message: {
            content: [{ type: 'tool_result', tool_use_id: 'tool-json-str', content: 'output text', is_error: false }],
          },
        }) as unknown as ClaudeMessage['raw'],
      },
    ];

    const currentSessionIdRef = { current: 'ses_1' };
    const { result } = renderHook(() =>
      useChatComputations({
        t: ((k: string) => k) as any,
        messages,
        mergedMessages: messages,
        subagentHistories: {},
        customSessionTitle: null,
        streamingActive: false,
        currentProvider: 'opencode',
        currentSessionId: 'ses_1',
        currentSessionIdRef,
        getMessageText: (m) => m.content ?? '',
        getContentBlocks: () => [],
        sseTodos: null,
      })
    );

    const hit = result.current.findToolResult('tool-json-str', 0);
    expect(hit).toBeDefined();
    expect(hit?.type).toBe('tool_result');
    expect(hit?.tool_use_id).toBe('tool-json-str');
    expect(hit?.content).toBe('output text');
  });

  it('sessionTitle returns default newSession even when user messages exist without customSessionTitle', async () => {
    const { renderHook } = await import('@testing-library/react');
    const { useChatComputations } = await import('./useChatComputations');

    const messages: ClaudeMessage[] = [
      {
        type: 'user',
        content: 'hi, can you help me write code?',
      },
    ];

    const currentSessionIdRef = { current: 'ses_2' };
    const { result, rerender } = renderHook(
      ({ customTitle }: { customTitle: string | null }) =>
        useChatComputations({
          t: ((k: string) => (k === 'common.newSession' ? '新会话' : k)) as any,
          messages,
          mergedMessages: messages,
          subagentHistories: {},
          customSessionTitle: customTitle,
          streamingActive: false,
          currentProvider: 'opencode',
          currentSessionId: 'ses_2',
          currentSessionIdRef,
          getContentBlocks: () => [],
          sseTodos: null,
        }),
      { initialProps: { customTitle: null } }
    );

    // Should NOT extract 'hi, can you he...' optimistically; should strictly remain '新会话'
    expect(result.current.sessionTitle).toBe('新会话');

    // When server/OpenCode updates the title via customSessionTitle (e.g. 'Greeting')
    rerender({ customTitle: 'Greeting' });
    expect(result.current.sessionTitle).toBe('Greeting');
  });
});

