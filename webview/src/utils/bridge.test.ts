import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  openBrowser,
  openClass,
  openFile,
  showEditableDiff,
  showInteractiveDiff,
  undoFileChanges,
} from './bridge';

describe('bridge navigation helpers', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  /** Every command travels on one envelope; assertions read type + payload. */
  const sentCommands = () => ((window.sendToJava as unknown as ReturnType<typeof vi.fn>).mock.calls)
    .map((call: unknown[]) => {
      const envelope = JSON.parse((call[0] as string).substring('host_command:'.length));
      return { type: envelope.type, payload: envelope.payload };
    });

  it('decodes percent-encoded navigation paths for openFile', () => {
    openFile('/Users/demo/my%20file.ts');
    openFile('/Users/demo/%C3%BCber.txt');
    openFile('file:///Users/demo/my%20file.ts');

    expect(sentCommands()).toEqual([
      { type: 'open_file', payload: '/Users/demo/my file.ts' },
      { type: 'open_file', payload: '/Users/demo/über.txt' },
      { type: 'open_file', payload: '/Users/demo/my file.ts' },
    ]);
  });

  it('parses line numbers from normalized navigation paths', () => {
    openFile('/Users/demo/my%20file.ts:42');

    expect(sentCommands()).toEqual([
      { type: 'open_file', payload: '/Users/demo/my file.ts:42' },
    ]);
  });

  it('allows relative navigation paths for openFile', () => {
    openFile('../shared/utils.ts');
    expect(sentCommands()).toEqual([
      { type: 'open_file', payload: '../shared/utils.ts' },
    ]);
  });

  it('sends openClass for valid Java FQCN values', () => {
    openClass('com.github.claudecodegui.handler.file.OpenFileHandler');
    expect(sentCommands()).toEqual([
      { type: 'open_class', payload: 'com.github.claudecodegui.handler.file.OpenFileHandler' },
    ]);
  });

  it('shares the same trimmed FQCN validation rules as linkify', () => {
    openClass(' com.github.foo.BarService ');
    openClass('com.github.foo.Outer.Inner');
    openClass('org.junit.jupiter.api');
    openClass('com.github.foo.Bar.baz()');

    expect(sentCommands()).toEqual([
      { type: 'open_class', payload: 'com.github.foo.BarService' },
      { type: 'open_class', payload: 'com.github.foo.Outer.Inner' },
    ]);
    expect(window.sendToJava).toHaveBeenCalledTimes(2);
  });

  it('rejects invalid Java class expressions', () => {
    openClass('com.github.foo.Bar#baz');
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  it('keeps traversal guards for mutating file APIs', () => {
    showEditableDiff('../shared/utils.ts', [], 'M');
    showInteractiveDiff('../shared/utils.ts', 'next');
    undoFileChanges('../shared/utils.ts', 'M', []);

    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  it('allows http, https, and mailto protocols for openBrowser', () => {
    openBrowser('https://example.com/docs');
    openBrowser('http://example.com');
    openBrowser('mailto:test@example.com');

    expect(sentCommands()).toEqual([
      { type: 'open_browser', payload: 'https://example.com/docs' },
      { type: 'open_browser', payload: 'http://example.com' },
      { type: 'open_browser', payload: 'mailto:test@example.com' },
    ]);
  });

  it('rejects file: and javascript: protocols in openBrowser', () => {
    openBrowser('file:///etc/passwd');
    openBrowser('javascript:alert(1)');

    expect(window.sendToJava).not.toHaveBeenCalled();
  });
});
