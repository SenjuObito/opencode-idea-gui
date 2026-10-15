import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ChatHeader } from './ChatHeader';
import type { SessionTabData } from './types';

const mockT = ((key: string) => key) as any;

describe('ChatHeader', () => {
  const mockTabs: SessionTabData[] = [
    {
      id: 'tab-1',
      sessionId: 'sess-1',
      title: 'Tab 1',
      messages: [],
      isShared: false,
    },
    {
      id: 'tab-2',
      sessionId: 'sess-2',
      title: 'Tab 2',
      messages: [],
      isShared: true,
    },
  ];

  const defaultProps = {
    currentView: 'chat' as const,
    t: mockT,
    tabs: mockTabs,
    activeTabId: 'tab-1',
    onSelectTab: vi.fn(),
    onCloseTab: vi.fn(),
    onRenameTab: vi.fn(),
    onForkTab: vi.fn(),
    onShareTab: vi.fn(),
    onCopyShareLinkTab: vi.fn(),
    onUnshareTab: vi.fn(),
    onBack: vi.fn(),
    onNewSession: vi.fn(),
    onHistory: vi.fn(),
    onSettings: vi.fn(),
  };

  it('renders session tabs and triggers onSelectTab when clicked', () => {
    const onSelectTab = vi.fn();
    render(<ChatHeader {...defaultProps} onSelectTab={onSelectTab} />);

    const tab2 = screen.getByText('Tab 2');
    expect(tab2).toBeTruthy();

    fireEvent.click(tab2);
    expect(onSelectTab).toHaveBeenCalledWith('tab-2');
  });

  it('renders new session button and triggers onNewSession when clicked', () => {
    const onNewSession = vi.fn();
    render(<ChatHeader {...defaultProps} onNewSession={onNewSession} />);

    const newSessionBtn = screen.getByLabelText('common.newSession');
    expect(newSessionBtn).toBeTruthy();

    fireEvent.click(newSessionBtn);
    expect(onNewSession).toHaveBeenCalledTimes(1);
  });

  it('renders history button and triggers onHistory when clicked', () => {
    const onHistory = vi.fn();
    render(<ChatHeader {...defaultProps} onHistory={onHistory} />);

    const historyBtn = screen.getByLabelText('common.history');
    expect(historyBtn).toBeTruthy();

    fireEvent.click(historyBtn);
    expect(onHistory).toHaveBeenCalledTimes(1);
  });

  it('renders more options menu and triggers settings from secondary menu', () => {
    const onSettings = vi.fn();
    render(<ChatHeader {...defaultProps} onSettings={onSettings} />);

    const moreBtn = screen.getByLabelText('common.more');
    expect(moreBtn).toBeTruthy();

    // Menu should be closed initially
    expect(screen.queryByRole('menu')).toBeNull();

    // Click more button to open menu
    fireEvent.click(moreBtn);
    expect(screen.getByRole('menu')).toBeTruthy();

    // Test settings button
    const settingsItem = screen.getByText('common.settings');
    fireEvent.click(settingsItem);
    expect(onSettings).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('renders back button in history view and triggers onBack', () => {
    const onBack = vi.fn();
    render(<ChatHeader {...defaultProps} currentView="history" onBack={onBack} />);

    const backBtn = screen.getByText('common.back');
    expect(backBtn).toBeTruthy();

    fireEvent.click(backBtn);
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('triggers onExportTab when clicking export in tab context menu', () => {
    const onExportTab = vi.fn();
    render(<ChatHeader {...defaultProps} onExportTab={onExportTab} />);

    // Right-click on tab
    const tabEl = screen.getByText('Tab 1');
    fireEvent.contextMenu(tabEl);

    // Context menu should appear with export item
    const exportBtn = screen.getByText('chat.exportMarkdown');
    expect(exportBtn).toBeTruthy();

    fireEvent.click(exportBtn);
    expect(onExportTab).toHaveBeenCalledWith(expect.objectContaining({ id: 'tab-1' }));
  });
});
