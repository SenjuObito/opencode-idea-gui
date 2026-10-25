import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../../utils/bridge', () => ({
  sendWindowCommand: vi.fn(() => true),
}));

import { ensureAgentsLoaded, getSubagentsSync, resetAgentsState, subagentMentionProvider } from './agentProvider';
import { sendWindowCommand } from '../../../utils/bridge';
import { dispatchHostEvent, resetHostDispatcher } from '../../../utils/hostDispatcher';
import { resetHostEvents } from '../../../utils/hostEventRegistry';

const agentsJson = JSON.stringify([
  { id: 'general', name: 'General', mode: 'subagent', description: 'General agent' },
  { id: 'explore', name: 'Explore', mode: 'subagent', description: 'Explore agent' },
]);

describe('agentProvider preload', () => {
  beforeEach(() => {
    resetAgentsState();
    resetHostEvents();
    resetHostDispatcher();
    vi.clearAllMocks();
  });

  it('registers the reply handler before sending the preload request', () => {
    ensureAgentsLoaded();

    expect(sendWindowCommand).toHaveBeenCalledWith('agents.list');

    dispatchHostEvent('agents.updated', [agentsJson]);

    expect(getSubagentsSync().map((agent) => agent.name)).toEqual(['General', 'Explore']);
  });

  it('accepts a named-event payload that still carries the JSON in `data`', () => {
    ensureAgentsLoaded();

    dispatchHostEvent('agents.updated', [{ data: agentsJson }]);

    expect(getSubagentsSync().map((agent) => agent.name)).toEqual(['General', 'Explore']);
  });

  it('serves the @ dropdown from the preloaded cache without waiting for a timeout', async () => {
    ensureAgentsLoaded();
    dispatchHostEvent('agents.updated', [agentsJson]);

    const items = await subagentMentionProvider('', new AbortController().signal);

    expect(items.map((item) => item.name)).toEqual(['General', 'Explore']);
  });
});
