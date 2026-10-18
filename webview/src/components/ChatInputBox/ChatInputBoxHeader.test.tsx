import type { ComponentProps } from 'react';
import { render } from '@testing-library/react';
import { ChatInputBoxHeader } from './ChatInputBoxHeader';

vi.mock('../../contexts/UIStateContext', () => ({
  useUIState: () => ({ addToast: vi.fn() }),
}));

const createProps = (): ComponentProps<typeof ChatInputBoxHeader> => ({
  daemonStatusLoaded: true,
  daemonAlive: true,
  t: ((key: string) => key) as never,
  attachments: [],
  onRemoveAttachment: vi.fn(),
  usagePercentage: 0,
  showUsage: false,
  onAddAttachment: vi.fn(),
  statusPanelExpanded: false,
});

describe('ChatInputBoxHeader', () => {
  it('renders without SDK warning bar', () => {
    render(<ChatInputBoxHeader {...createProps()} />);

    // SDK warning bar should not be present
    expect(document.querySelector('.sdk-warning-bar')).toBeNull();
  });

  it('renders StatusNoticeBar banner at top of header when quota error is passed', () => {
    const errorMsg = {
      type: 'error' as const,
      content: 'Rate limit exceeded. Please try again later.',
      timestamp: Date.now(),
    };

    render(
      <ChatInputBoxHeader
        {...createProps()}
        messages={[errorMsg as any]}
        getMessageText={(m) => (typeof m.content === 'string' ? m.content : '')}
      />
    );

    const banner = document.querySelector('.status-notice-bar--error');
    expect(banner).toBeTruthy();
    expect(banner?.textContent).toContain('Rate limit exceeded. Please try again later.');
  });
});
