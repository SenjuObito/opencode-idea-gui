import { beforeEach, describe, expect, it, vi } from 'vitest';

const sendWindowCommandMock = vi.fn();

vi.mock('./bridge', () => ({
  sendWindowCommand: (...args: unknown[]) => sendWindowCommandMock(...(args as [])),
}));

import type { UiPreferences as Prefs } from './uiPreferences';

type UiPreferencesModule = typeof import('./uiPreferences');

let mod: UiPreferencesModule;

/**
 * 每个用例都要一份干净的模块实例：模块在导入时就读取
 * `__INITIAL_UI_PREFERENCES__` 与 localStorage，并在模块级保存当前值。
 */
async function loadModule(initial?: Partial<Prefs>): Promise<UiPreferencesModule> {
  vi.resetModules();
  if (initial) {
    (window as unknown as { __INITIAL_UI_PREFERENCES__?: unknown }).__INITIAL_UI_PREFERENCES__ = initial;
  } else {
    delete (window as unknown as { __INITIAL_UI_PREFERENCES__?: unknown }).__INITIAL_UI_PREFERENCES__;
  }
  mod = await import('./uiPreferences');
  return mod;
}

function lastSent(): Record<string, unknown> | null {
  for (let i = sendWindowCommandMock.mock.calls.length - 1; i >= 0; i--) {
    const call = sendWindowCommandMock.mock.calls[i] as unknown[];
    if (call[0] === 'settings.ui.preferences') {
      return call[1] as Record<string, unknown>;
    }
  }
  return null;
}

describe('uiPreferences', () => {
  beforeEach(() => {
    localStorage.clear();
    sendWindowCommandMock.mockClear();
  });

  it('starts from defaults when neither host nor localStorage has values', async () => {
    const m = await loadModule();
    expect(m.getUiPreferences()).toEqual(m.DEFAULT_UI_PREFERENCES);
  });

  it('adopts the host-injected snapshot when localStorage is empty', async () => {
    const m = await loadModule({ theme: 'light', fontSizeLevel: 5 });
    expect(m.getUiPreferences().theme).toBe('light');
    expect(m.getUiPreferences().fontSizeLevel).toBe(5);
  });

  it('keeps a persisted localStorage choice over the host default on first read', async () => {
    localStorage.setItem('theme', 'light');
    localStorage.setItem('fontSizeLevel', '4');
    // 宿主还没有偏好副本（老用户升级场景），localStorage 不能被默认值打回。
    const m = await loadModule({ theme: 'system', fontSizeLevel: 2 });
    expect(m.getUiPreferences().theme).toBe('light');
    expect(m.getUiPreferences().fontSizeLevel).toBe(4);
  });

  it('mirrors a local change into localStorage and notifies the host once', async () => {
    const m = await loadModule();
    m.updateUiPreferences({ theme: 'dark' });

    expect(m.getUiPreferences().theme).toBe('dark');
    expect(localStorage.getItem('theme')).toBe('dark');
    expect(lastSent()?.theme).toBe('dark');
  });

  it('does not re-send an unchanged value to the host', async () => {
    const m = await loadModule();
    m.updateUiPreferences({ theme: 'dark' });
    const before = sendWindowCommandMock.mock.calls.length;
    m.updateUiPreferences({ theme: 'dark' });

    expect(sendWindowCommandMock.mock.calls.length).toBe(before);
  });

  it('applies an authoritative host snapshot and broadcasts it as host-sourced', async () => {
    const m = await loadModule();
    const events: Array<string | undefined> = [];
    const listener = (event: Event) => {
      events.push((event as CustomEvent<{ source?: string }>).detail?.source);
    };
    window.addEventListener(m.UI_PREFERENCES_CHANGED_EVENT, listener);

    m.applyHostUiPreferences({ theme: 'light', fontSizeLevel: 5, chatBgColor: '#123456' });

    expect(m.getUiPreferences().theme).toBe('light');
    expect(m.getUiPreferences().fontSizeLevel).toBe(5);
    expect(m.getUiPreferences().chatBgColor).toBe('#123456');
    expect(localStorage.getItem('theme')).toBe('light');
    expect(events).toContain('host');

    window.removeEventListener(m.UI_PREFERENCES_CHANGED_EVENT, listener);
  });

  it('sanitizes malformed host values instead of poisoning the UI', async () => {
    const m = await loadModule();
    m.applyHostUiPreferences({
      theme: 'neon',
      fontSizeLevel: 99,
      chatBgColor: 'red',
      diffTheme: 'hack',
      historyCompletionEnabled: 'yes',
      detailedOutputEnabled: 'y',
      skipCompactConfirm: 1,
    });

    const prefs = m.getUiPreferences();
    expect(prefs.theme).toBe('system');
    expect(prefs.fontSizeLevel).toBe(6);
    expect(prefs.chatBgColor).toBe('');
    expect(prefs.diffTheme).toBe('follow');
    // 非布尔值一律丢弃，回落到各字段默认值（历史补全默认开、其余默认关）。
    expect(prefs.historyCompletionEnabled).toBe(true);
    expect(prefs.detailedOutputEnabled).toBe(false);
    expect(prefs.skipCompactConfirm).toBe(false);
  });

  it('ignores non-object payloads', async () => {
    const m = await loadModule();
    expect(() => m.applyHostUiPreferences('garbage')).not.toThrow();
    expect(() => m.applyHostUiPreferences(null)).not.toThrow();
    expect(m.getUiPreferences()).toEqual(m.DEFAULT_UI_PREFERENCES);
  });

  it('installs the settings.ui.preferences handler and parses its JSON payload', async () => {
    const m = await loadModule();
    const registry = await import('./hostEventRegistry');
    registry.resetHostEvents();
    const dispose = m.installUiPreferencesBridge();
    registry.hostEventHandler('settings.ui.preferences')?.(JSON.stringify({ theme: 'dark', detailedOutputEnabled: true }));

    expect(m.getUiPreferences().theme).toBe('dark');
    expect(m.getUiPreferences().detailedOutputEnabled).toBe(true);

    dispose();
    expect(registry.hostEventHandler('settings.ui.preferences')).toBeNull();
  });
});
