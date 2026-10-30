import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const sendWindowCommandMock = vi.fn();

vi.mock('./bridge', () => ({
  sendWindowCommand: (...args: unknown[]) => sendWindowCommandMock(...(args as [])),
}));

import { downloadJSON, downloadMarkdown } from './exportMarkdown';

describe('exportMarkdown', () => {
  beforeEach(() => {
    sendWindowCommandMock.mockReset();
    // The host path is taken when the legacy bridge global exists.
    (window as unknown as { sendToJava?: unknown }).sendToJava = vi.fn();
  });

  afterEach(() => {
    delete (window as unknown as { sendToJava?: unknown }).sendToJava;
  });

  it('uses save_markdown so the host keeps the .md extension', () => {
    downloadMarkdown('# Hello\n\nbody', 'My Session');

    expect(sendWindowCommandMock).toHaveBeenCalledTimes(1);
    expect(sendWindowCommandMock).toHaveBeenCalledWith('save_markdown', {
      content: '# Hello\n\nbody',
      filename: 'My Session.md',
    });
  });

  it('sanitizes the markdown filename', () => {
    downloadMarkdown('body', 'a/b:c');

    expect(sendWindowCommandMock).toHaveBeenCalledWith('save_markdown', {
      content: 'body',
      filename: 'a_b_c.md',
    });
  });

  it('keeps save_json for JSON exports', () => {
    downloadJSON('{"a":1}', 'session');

    expect(sendWindowCommandMock).toHaveBeenCalledWith('save_json', {
      content: '{"a":1}',
      filename: 'session.json',
    });
  });
});
