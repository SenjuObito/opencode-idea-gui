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
});
