/**
 * permissionPromptRouting.test.ts
 *
 * 问题弹窗 / 权限弹窗的事件投递。
 *
 * 两个真实故障由这组用例看守：
 *
 * 1. 「弹窗不弹，只看到一张已取消卡片」——带 id 的请求必须进入对话框层，
 *    由它按 `tabId` 落到所属 tab；找不到归属 tab 时落到当前 tab，绝不丢弃。
 * 2. 「弹窗弹到别的会话」——归属由对话框层统一决定（tab 记录是唯一队列），
 *    注册回调不再各自维护一套入队逻辑。
 *
 * 队列归属（后台 tab 挂起、切过去再弹、关闭后从所有 tab 摘掉）由
 * `useDialogManagement.forceClose.test.ts` 覆盖。
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { registerPermissionCallbacks } from './permissionCallbacks';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { hostEventHandler } from '../../../utils/hostEventRegistry';
import { resetTabStore } from '../../../store/tabStore';

vi.mock('../../../utils/bridge', () => ({
  cardDebugLog: vi.fn(),
  sendBridgeEvent: vi.fn(),
}));

function createHarness() {
  const openPermissionDialog = vi.fn();
  const openAskUserQuestionDialog = vi.fn();
  const openPlanApprovalDialog = vi.fn();
  const forceClosePermissionDialog = vi.fn();
  const forceCloseAskUserQuestionDialog = vi.fn();
  const forceClosePlanApprovalDialog = vi.fn();
  const addToast = vi.fn();

  const options = {
    addToast,
    openPermissionDialog,
    openAskUserQuestionDialog,
    openPlanApprovalDialog,
    forceClosePermissionDialog,
    forceCloseAskUserQuestionDialog,
    forceClosePlanApprovalDialog,
  } as unknown as UseWindowCallbacksOptions;

  registerPermissionCallbacks(options);

  return {
    openPermissionDialog,
    openAskUserQuestionDialog,
    openPlanApprovalDialog,
    forceClosePermissionDialog,
    forceCloseAskUserQuestionDialog,
    forceClosePlanApprovalDialog,
    addToast,
  };
}

const permissionJson = (channelId: string, sessionId: string | null) =>
  JSON.stringify({
    channelId,
    toolName: 'Bash',
    sessionId: sessionId ?? undefined,
    inputs: { command: 'ls' },
  });

const questionJson = (requestId: string, sessionId: string | null) =>
  JSON.stringify({
    requestId,
    toolName: 'ask',
    sessionId: sessionId ?? undefined,
    questions: [{ question: 'pick one', options: ['a', 'b'] }],
  });

beforeEach(() => {
  resetTabStore();
});

afterEach(() => {
  resetTabStore();
  vi.restoreAllMocks();
});

describe('permission.requested — 投递到对话框层', () => {
  it('带 id 的请求原样投递（归属 tab 由对话框层决定）', () => {
    const h = createHarness();

    hostEventHandler('permission.requested')!(JSON.parse(permissionJson('per_b1', 'ses_b')));

    expect(h.openPermissionDialog).toHaveBeenCalledTimes(1);
    expect(h.openPermissionDialog.mock.calls[0][0]).toMatchObject({
      channelId: 'per_b1',
      sessionId: 'ses_b',
    });
  });

  it('没有身份的请求直接丢弃（无从答复，弹出来只会挂死）', () => {
    const h = createHarness();

    expect(() => hostEventHandler('permission.requested')!(undefined)).not.toThrow();
    expect(h.openPermissionDialog).not.toHaveBeenCalled();
  });

  it('prompt.closed 交给 forceClose（由对话框层从所有 tab 摘掉）', () => {
    const h = createHarness();

    hostEventHandler('prompt.closed')!({ requestId: 'per_b1' });

    expect(h.forceClosePermissionDialog).toHaveBeenCalledWith('per_b1');
  });
});

describe('question.requested — 投递到对话框层', () => {
  it('带 id 的提问原样投递', () => {
    const h = createHarness();

    hostEventHandler('question.requested')!(JSON.parse(questionJson('q_b1', 'ses_b')));

    expect(h.openAskUserQuestionDialog).toHaveBeenCalledTimes(1);
    expect(h.openAskUserQuestionDialog.mock.calls[0][0]).toMatchObject({
      requestId: 'q_b1',
      sessionId: 'ses_b',
    });
  });

  it('prompt.question.closed 交给 forceClose', () => {
    const h = createHarness();

    hostEventHandler('prompt.question.closed')!({ requestId: 'q_b1' });

    expect(h.forceCloseAskUserQuestionDialog).toHaveBeenCalledWith('q_b1');
  });
});

describe('plan.requested — 投递到对话框层', () => {
  it('带 id 的计划审批原样投递', () => {
    const h = createHarness();

    hostEventHandler('plan.requested')!({
      requestId: 'plan_1',
      toolName: 'ExitPlanMode',
      tabId: 'tab-b',
      sessionId: 'ses_b',
    });

    expect(h.openPlanApprovalDialog).toHaveBeenCalledTimes(1);
    expect(h.openPlanApprovalDialog.mock.calls[0][0]).toMatchObject({
      requestId: 'plan_1',
      tabId: 'tab-b',
    });
  });
});
