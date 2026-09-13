import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  slashCommandProvider,
  commandToDropdownItem,
  resetSlashCommandsState,
  setupSlashCommandsCallback,
} from './slashCommandProvider';

vi.mock('../../../i18n/config', () => ({
  default: {
    t: (key: string) => key,
  },
}));

vi.mock('../../../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(() => true),
}));

describe('slashCommandProvider', () => {
  beforeEach(() => {
    resetSlashCommandsState();
    delete (window as any).updateSlashCommands;
  });

  it('filters builtin commands by query', async () => {
    setupSlashCommandsCallback();
    // Simulate backend sending commands list
    if (typeof (window as any).updateSlashCommands === 'function') {
      (window as any).updateSlashCommands(JSON.stringify([
        { name: 'init', description: 'Initialize repository' },
        { name: 'compact', description: 'Compact conversation' },
      ]));
    }

    const controller = new AbortController();
    const commands = await slashCommandProvider('comp', controller.signal);

    expect(commands.some((c) => c.label === '/compact')).toBe(true);
    expect(commands.some((c) => c.label === '/init')).toBe(false);
  });

  it('always includes local /clear command', async () => {
    setupSlashCommandsCallback();
    if (typeof (window as any).updateSlashCommands === 'function') {
      (window as any).updateSlashCommands(JSON.stringify([]));
    }

    const controller = new AbortController();
    const commands = await slashCommandProvider('clear', controller.signal);

    expect(commands.some((c) => c.label === '/clear')).toBe(true);
  });

  it('formats command to dropdown item properly', () => {
    const dropdown = commandToDropdownItem({
      id: 'compact',
      label: '/compact',
      description: 'Compact session',
      category: 'builtin',
    });

    expect(dropdown.id).toBe('compact');
    expect(dropdown.label).toBe('/compact');
    expect(dropdown.description).toBe('Compact session');
    expect(dropdown.type).toBe('command');
  });

  it('aborts when signal is already aborted', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(slashCommandProvider('', controller.signal)).rejects.toThrow('Aborted');
  });
});
