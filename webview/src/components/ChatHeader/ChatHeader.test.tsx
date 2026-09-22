import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ChatHeader } from './ChatHeader';

const mockT = ((key: string) => key) as any;

describe('ChatHeader share controls', () => {
  const defaultProps = {
    currentView: 'chat' as const,
    sessionTitle: 'Test Session',
    t: mockT,
    onBack: vi.fn(),
    onNewSession: vi.fn(),
    onHistory: vi.fn(),
    onSettings: vi.fn(),
    onShare: vi.fn(),
    onUnshare: vi.fn(),
    onCopyShareLink: vi.fn(),
  };

  it('renders unshared button and triggers onShare when clicked', () => {
    const onShare = vi.fn();
    render(<ChatHeader {...defaultProps} isShared={false} onShare={onShare} />);

    const shareBtn = screen.getByLabelText('chat.shareTooltip');
    expect(shareBtn).toBeTruthy();

    fireEvent.click(shareBtn);
    expect(onShare).toHaveBeenCalledTimes(1);
  });

  it('renders loading button when sharePending is true', () => {
    render(<ChatHeader {...defaultProps} sharePending={true} />);

    const pendingBtn = screen.getByLabelText('chat.sharePendingTooltip');
    expect(pendingBtn).toBeTruthy();
    expect(pendingBtn.hasAttribute('disabled')).toBe(true);
  });

  it('renders copy link and unshare buttons when isShared is true', () => {
    const onCopyShareLink = vi.fn();
    const onUnshare = vi.fn();
    render(
      <ChatHeader
        {...defaultProps}
        isShared={true}
        onCopyShareLink={onCopyShareLink}
        onUnshare={onUnshare}
      />
    );

    const copyBtn = screen.getByLabelText('chat.copyShareLinkTooltip');
    const unshareBtn = screen.getByLabelText('chat.unshareTooltip');

    expect(copyBtn).toBeTruthy();
    expect(unshareBtn).toBeTruthy();

    fireEvent.click(copyBtn);
    expect(onCopyShareLink).toHaveBeenCalledTimes(1);

    fireEvent.click(unshareBtn);
    expect(onUnshare).toHaveBeenCalledTimes(1);
  });

  it('hides share controls when shareHidden is true', () => {
    render(<ChatHeader {...defaultProps} shareHidden={true} />);

    expect(screen.queryByLabelText('chat.shareTooltip')).toBeNull();
    expect(screen.queryByLabelText('chat.copyShareLinkTooltip')).toBeNull();
    expect(screen.queryByLabelText('chat.unshareTooltip')).toBeNull();
  });

  it('renders more options menu and triggers export and settings from secondary menu', () => {
    const onExport = vi.fn();
    const onSettings = vi.fn();
    render(<ChatHeader {...defaultProps} onExport={onExport} onSettings={onSettings} />);

    const moreBtn = screen.getByLabelText('common.more');
    expect(moreBtn).toBeTruthy();

    // Menu should be closed initially
    expect(screen.queryByRole('menu')).toBeNull();

    // Click more button to open menu
    fireEvent.click(moreBtn);
    expect(screen.getByRole('menu')).toBeTruthy();

    // Find export button and click
    const exportItem = screen.getByText('chat.exportMarkdown');
    fireEvent.click(exportItem);
    expect(onExport).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();

    // Open menu again and test settings
    fireEvent.click(moreBtn);
    expect(screen.getByRole('menu')).toBeTruthy();

    const settingsItem = screen.getByText('common.settings');
    fireEvent.click(settingsItem);
    expect(onSettings).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('menu')).toBeNull();
  });
});
