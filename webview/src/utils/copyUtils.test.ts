import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { copyViaHost, extractMarkdownContent } from './copyUtils';
import type { ClaudeMessage } from '../types';

describe('copyUtils', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    (window as any).sendToJava = vi.fn();
  });

  afterEach(() => {
    vi.useRealTimers();
    delete (window as any).sendToJava;
  });

  describe('copyViaHost', () => {
    it('sends write_clipboard bridge event and resolves true when host acknowledges success', async () => {
      const sendToJavaMock = vi.fn();
      (window as any).sendToJava = sendToJavaMock;

      const promise = copyViaHost('https://opencode.ai/share/123');
      expect(sendToJavaMock).toHaveBeenCalledWith('write_clipboard:https://opencode.ai/share/123');

      // Simulate host callback
      window.onCopyToClipboardResult?.('true');

      const result = await promise;
      expect(result).toBe(true);
    });

    it('resolves false when host acknowledges failure', async () => {
      const sendToJavaMock = vi.fn();
      (window as any).sendToJava = sendToJavaMock;

      const promise = copyViaHost('https://opencode.ai/share/123');
      expect(sendToJavaMock).toHaveBeenCalledWith('write_clipboard:https://opencode.ai/share/123');

      // Simulate host failure callback
      window.onCopyToClipboardResult?.('false');

      const result = await promise;
      expect(result).toBe(false);
    });

    it('falls back to true after 2s timeout when no host ack received', async () => {
      (window as any).sendToJava = vi.fn();

      const promise = copyViaHost('https://opencode.ai/share/123');
      vi.advanceTimersByTime(2000);

      const result = await promise;
      expect(result).toBe(true);
    });
  });

  describe('extractMarkdownContent', () => {
    it('extracts plain text from message content', () => {
      const msg: ClaudeMessage = {
        id: '1',
        sender: 'assistant',
        content: 'Hello World',
      };
      expect(extractMarkdownContent(msg)).toBe('Hello World');
    });
  });
});
