import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import StatusPanel from './StatusPanel';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

describe('StatusPanel', () => {
  it.each([
    ['claude', 'statusPanel.tasksTab'],
  ])('uses the provider-specific todo label for %s', (currentProvider, expectedLabel) => {
    render(
      <StatusPanel
        todos={[]}
        fileChanges={[]}
        subagents={[]}
        currentProvider={currentProvider}
      />,
    );

    expect(screen.getByText(expectedLabel)).toBeTruthy();
  });

  it('lets the active tab own the open StatusPanel page', () => {
    const onActiveTabChange = vi.fn();
    render(
      <StatusPanel
        todos={[]}
        fileChanges={[]}
        subagents={[]}
        currentProvider="opencode"
        activeTab="todo"
        onActiveTabChange={onActiveTabChange}
      />,
    );

    expect(screen.getByText('statusPanel.noTodos')).toBeTruthy();
    fireEvent.click(screen.getByText('statusPanel.subagentTab'));

    expect(onActiveTabChange).toHaveBeenCalledWith('subagent');
    // Controlled by the tab: the panel does not switch itself.
    expect(screen.queryByText('statusPanel.noSubagents')).toBeNull();
  });
});
