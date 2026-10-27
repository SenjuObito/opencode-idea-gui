import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SETTINGS_BOOTSTRAP_COMMANDS,
  useSettingsWindowCallbacks,
  type SettingsWindowCallbacksDeps,
} from './useSettingsWindowCallbacks';
import { hostEventHandler, registerHostEvents, resetHostEvents } from '../../../utils/hostEventRegistry';
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('useSettingsWindowCallbacks', () => {
  const createDeps = (): SettingsWindowCallbacksDeps => ({
    setClaudeCliPath: vi.fn(),
    setSavingClaudeCliPath: vi.fn(),
    setWorkingDirectory: vi.fn(),
    setSavingWorkingDirectory: vi.fn(),
    setEditorFontConfig: vi.fn(),
    setUiFontConfig: vi.fn(),
    setCodeFontConfig: vi.fn(),
    setIdeTheme: vi.fn(),
    setLocalSendShortcut: vi.fn(),
    setSoundNotificationEnabled: vi.fn(),
    setSoundOnlyWhenUnfocused: vi.fn(),
    setSelectedSound: vi.fn(),
    setCustomSoundPath: vi.fn(),
    setSystemNotificationOnlyWhenUnfocused: vi.fn(),
    setAskUserQuestionSoundNotificationEnabled: vi.fn(),
    handleAgentOperationResult: vi.fn(),
    handleAgentImportPreviewResult: vi.fn(),
    handleAgentImportResult: vi.fn(),
    showAlert: vi.fn(),
    addToast: vi.fn(),
  });

  beforeEach(() => {
    vi.useFakeTimers();
    // Force setTimeout path so fake timers control deferred batches. Asserting
    // to a plain optional-field type keeps delete legal: intersecting with
    // Window re-introduces lib.dom's required requestIdleCallback signature.
    delete (window as { requestIdleCallback?: unknown }).requestIdleCallback;
    delete (window as { cancelIdleCallback?: unknown }).cancelIdleCallback;
    window.sendToJava = vi.fn();
    resetHostEvents();
    registerHostEvents({
      'font.ui.apply': vi.fn(),
      'font.code.apply': vi.fn(),
    });
  });

  afterEach(() => {
    vi.useRealTimers();
    resetHostEvents();
  });

  /** The command types the settings panel sent, in order. */
  const sentTypes = () => (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls
    .map((call) => call[0] as string)
    .filter((payload) => payload.startsWith('host_command:'))
    .map((payload) => JSON.parse(payload.substring('host_command:'.length)).type as string);

  it('loads basic settings in batches without stamping providers/agents or CLI-probe configs', () => {
    const deps = createDeps();

    renderHook(() => useSettingsWindowCallbacks(deps));

    // Heavy list fetches are deferred until their settings tabs open.
    expect(sentTypes()).not.toContain('agents.list');
    expect(sentTypes()).not.toContain('get_current_claude_config');

    // First batch only (batchSize = 5), priority fields for first paint.
    const firstBatch = SETTINGS_BOOTSTRAP_COMMANDS.slice(0, 5);
    expect(sentTypes()).toEqual([...firstBatch]);

    // Drain deferred batches.
    vi.runAllTimers();
    const allCommands = sentTypes();
    for (const command of SETTINGS_BOOTSTRAP_COMMANDS) {
      expect(allCommands).toContain(command);
    }

    // CLI availability probes freeze JCEF when done on open — keep off bootstrap.
    expect(allCommands).not.toContain('get_commit_prompt');
    expect(allCommands).not.toContain('get_prompt_enhancer_config');
  });

  it('registers ui font callback and updates ui font state from backend payload', () => {
    const deps = createDeps();

    renderHook(() => useSettingsWindowCallbacks(deps));

    hostEventHandler('font.ui.received')?.(JSON.stringify({
      mode: 'customFile',
      effectiveMode: 'customFile',
      customFontPath: '/tmp/MapleMono.ttf',
      fontFamily: 'CC GUI Custom',
      fontSize: 14,
      lineSpacing: 1.35,
    }));

    expect((deps as any).setUiFontConfig).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'customFile',
      customFontPath: '/tmp/MapleMono.ttf',
      fontFamily: 'CC GUI Custom',
    }));
  });

  it('registers code font callback and updates code font state from backend payload', () => {
    const deps = createDeps();

    renderHook(() => useSettingsWindowCallbacks(deps));

    hostEventHandler('font.code.received')?.(JSON.stringify({
      mode: 'customFile',
      effectiveMode: 'customFile',
      customFontPath: '/tmp/FiraCode.ttf',
      fontFamily: 'CC GUI Code Custom',
      fontSize: 14,
      lineSpacing: 1.35,
    }));

    expect((deps as any).setCodeFontConfig).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'customFile',
      customFontPath: '/tmp/FiraCode.ttf',
      fontFamily: 'CC GUI Code Custom',
    }));
  });

  it('applies ui font immediately when backend pushes updated config', () => {
    const deps = createDeps();

    renderHook(() => useSettingsWindowCallbacks(deps));

    const payload = {
      mode: 'customFile',
      effectiveMode: 'customFile',
      customFontPath: '/tmp/MapleMono.ttf',
      fontFamily: 'CC GUI Custom',
      fontSize: 14,
      lineSpacing: 1.35,
      fontBase64: 'AAECA',
      fontFormat: 'truetype',
    };

    hostEventHandler('font.ui.received')?.(JSON.stringify(payload));

    expect(hostEventHandler('font.ui.apply')).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'customFile',
      customFontPath: '/tmp/MapleMono.ttf',
      fontBase64: 'AAECA',
      fontFormat: 'truetype',
    }));
  });

  it('applies code font immediately when backend pushes updated config', () => {
    const deps = createDeps();

    renderHook(() => useSettingsWindowCallbacks(deps));

    const payload = {
      mode: 'customFile',
      effectiveMode: 'customFile',
      customFontPath: '/tmp/FiraCode.ttf',
      fontFamily: 'CC GUI Code Custom',
      fontSize: 14,
      lineSpacing: 1.35,
      fontBase64: 'AAECA',
      fontFormat: 'truetype',
    };

    hostEventHandler('font.code.received')?.(JSON.stringify(payload));

    expect(hostEventHandler('font.code.apply')).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'customFile',
      customFontPath: '/tmp/FiraCode.ttf',
      fontBase64: 'AAECA',
      fontFormat: 'truetype',
    }));
  });

  it('registers system notification focus gate callback and updates state from backend payload', () => {
    const deps = createDeps();

    renderHook(() => useSettingsWindowCallbacks(deps));

    hostEventHandler('settings.system.notification')?.(JSON.stringify({
      systemNotificationOnlyWhenUnfocused: true,
    }));

    expect(deps.setSystemNotificationOnlyWhenUnfocused).toHaveBeenCalledWith(true);
  });
});
