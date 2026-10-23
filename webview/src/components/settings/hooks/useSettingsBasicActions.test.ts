import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { useSettingsBasicActions } from './useSettingsBasicActions';
import type { CodeFontConfig } from '../../../types/uiFontConfig';

describe('useSettingsBasicActions', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  /** Commands share one envelope: read them back by type. */
  const sentEnvelopes = () => ((window.sendToJava as unknown as ReturnType<typeof vi.fn>).mock.calls)
    .map((call: unknown[]) => call[0] as string)
    .filter((raw: string) => raw.startsWith('host_command:'))
    .map((raw: string) => JSON.parse(raw.substring('host_command:'.length)));

  const sentTypes = () => sentEnvelopes().map((envelope) => envelope.type);

  const sentPayload = (type: string) =>
    sentEnvelopes().find((envelope) => envelope.type === type)?.payload;

  it('sends independent code font updates without mutating ui font state', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.handleCodeFontSelectionChange('followEditor');
    });

    expect(sentTypes()).not.toContain('set_ui_font_config');
    expect(sentPayload('set_code_font_config')).toEqual({ mode: 'followEditor' });
  });

  it('sends a code font customFile update when a saved path exists', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    const customCodeFontConfig: CodeFontConfig = {
      mode: 'customFile',
      effectiveMode: 'customFile',
      customFontPath: '/tmp/my-code-font.ttf',
      fontFamily: 'CC GUI Code Custom',
      fontSize: 13,
      lineSpacing: 1,
    };

    act(() => {
      result.current.setCodeFontConfig(customCodeFontConfig);
    });

    act(() => {
      result.current.handleCodeFontSelectionChange('customFile');
    });

    expect(sentPayload('set_code_font_config')).toEqual({
      mode: 'customFile',
      customFontPath: '/tmp/my-code-font.ttf',
    });
  });

  it('does not send anything when switching to customFile without a saved path (silent no-op)', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    (window.sendToJava as any).mockClear();

    act(() => {
      result.current.handleCodeFontSelectionChange('customFile');
    });

    expect(sentTypes()).not.toContain('set_code_font_config');
  });

  it('sends named font configuration updates for ui and code font selections', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.handleUiFontSelectionChange('named:JetBrains Mono');
      result.current.handleCodeFontSelectionChange('named:Fira Code');
    });

    expect(sentPayload('set_ui_font_config')).toEqual({
      mode: 'named',
      fontFamily: 'JetBrains Mono',
    });
    expect(sentPayload('set_code_font_config')).toEqual({
      mode: 'named',
      fontFamily: 'Fira Code',
    });
  });
});
