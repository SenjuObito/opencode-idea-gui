import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import BashToolBlock from './BashToolBlock';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

let mockIsDenied = false;
vi.mock('../../hooks/useIsToolDenied', () => ({
  useIsToolDenied: () => mockIsDenied,
}));

describe('BashToolBlock', () => {
  it('hides empty placeholders until command details arrive', () => {
    mockIsDenied = false;
    const { container, rerender } = render(<BashToolBlock input={{}} />);

    expect(container.firstChild).toBeNull();

    rerender(<BashToolBlock input={{ command: 'npm test' }} />);
    expect(container.querySelector('.bash-tool-header')).not.toBeNull();

    rerender(<BashToolBlock input={{ command: '  ', description: '\n' }} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders command and stdout text in code-font-targeted nodes', () => {
    mockIsDenied = false;
    const { container } = render(
      <BashToolBlock
        input={{
          command: 'node --version',
        }}
        result={{
          type: 'tool_result',
          content: 'v22.0.0',
        }}
      />,
    );

    fireEvent.click(container.querySelector('.bash-tool-header') as HTMLElement);

    expect(container.querySelector('.bash-command-block')?.textContent).toBe('node --version');
    expect(container.querySelector('.bash-output-text')?.textContent).toBe('v22.0.0');
    expect(container.querySelector('.tool-status-indicator.completed')).not.toBeNull();
    expect(container.querySelector('.tool-status-indicator.error')).toBeNull();
  });

  it('renders completed status even if isDenied was previously flagged when result is successful', () => {
    mockIsDenied = true;
    const { container } = render(
      <BashToolBlock
        input={{ command: 'ls -la' }}
        result={{
          type: 'tool_result',
          content: 'file.txt',
          is_error: false,
        }}
        toolId="call_123"
      />,
    );

    expect(container.querySelector('.tool-status-indicator.completed')).not.toBeNull();
    expect(container.querySelector('.tool-status-indicator.error')).toBeNull();
  });

  it('renders error status when result has is_error: true', () => {
    mockIsDenied = false;
    const { container } = render(
      <BashToolBlock
        input={{ command: 'invalid_cmd' }}
        result={{
          type: 'tool_result',
          content: 'command not found',
          is_error: true,
        }}
        toolId="call_456"
      />,
    );

    expect(container.querySelector('.tool-status-indicator.error')).not.toBeNull();
  });

  it('renders error status when result is missing and tool was denied', () => {
    mockIsDenied = true;
    const { container } = render(
      <BashToolBlock
        input={{ command: 'rm -rf /' }}
        toolId="call_denied"
      />,
    );

    expect(container.querySelector('.tool-status-indicator.error')).not.toBeNull();
  });
});
