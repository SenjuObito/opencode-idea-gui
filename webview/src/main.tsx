// Unified host event dispatcher must exist before any bridge listener runs.
import './utils/hostDispatcher';

// VS Code bridge: must load before React so window.sendToJava + message
// dispatch are installed before bridgeStartup's waitForBridge polls.
import './vscodeBridge';

import ReactDOM from 'react-dom/client';
import App from './App';
import ErrorBoundary from './components/ErrorBoundary';
import { onRetryStatusFromHost, onTerminalErrorFromHost } from './components/StatusNoticeBar';
import { MessagesProvider } from './contexts/MessagesContext';
import { SessionProvider } from './contexts/SessionContext';
import { UIStateProvider } from './contexts/UIStateContext';
import { DialogProvider } from './contexts/DialogContext';
import { TaskEventProvider } from './contexts/SubagentContext';
import './codicon.css';
import './styles/app.less';
import './i18n/config';
import i18n from './i18n/config';
import { setupSlashCommandsCallback } from './components/ChatInputBox/providers/slashCommandProvider';
import { setupDollarCommandsCallback } from './components/ChatInputBox/providers/dollarCommandProvider';
import { applyLinkifyCapabilitiesPayload } from './utils/linkifyCapabilities';
import { sendHostCommand, sendWindowCommand, cardDebugLog } from './utils/bridge';
import { installUiPreferencesBridge, requestUiPreferences } from './utils/uiPreferences';
import { installPinnedModelsBridge } from './components/ChatInputBox/modelSelectUtils';
import { debugLog } from './utils/debug';
import { waitForBridge } from './utils/bridgeStartup';
import type { UiFontConfig, CodeFontConfig } from './types/uiFontConfig';
import { playNotificationSound } from './utils/notificationSound';
import {
  beginSurfaceDamagePulse,
  advanceSurfaceDamagePulse,
  replaceSurfaceDamagePulse,
  finishSurfaceDamagePulse,
  cancelSurfaceDamagePulse,
} from './utils/surfaceDamagePulse';
import { forceWebviewRepaint } from './utils/forceWebviewRepaint';
import { registerHostEvents } from './utils/hostEventRegistry';
import type { HostEventPayload } from './utils/hostEventTypes';

// Silence noisy console output in production (including third-party libs).
// console.error is preserved so ErrorBoundary and unhandled exceptions still
// surface in the IDE's webview devtools — silencing it would hide regressions.
if (!import.meta.env.DEV) {
  const noop = () => {};
  console.log = noop;
  console.debug = noop;
  console.info = noop;
  console.warn = noop;
}

function createBridgeHeartbeatStarter() {
  let started = false;

  return () => {
    if (started) return;
    started = true;

    let lastRafAt = Date.now();
    let rafId: number | null = null;
    const rafLoop = () => {
      lastRafAt = Date.now();
      rafId = requestAnimationFrame(rafLoop);
    };
    rafId = requestAnimationFrame(rafLoop);

    let sequence = 0;
    const intervalMs = 5000;

    let intervalId: number | null = null;
    intervalId = window.setInterval(() => {
      sequence += 1;
      const payload = JSON.stringify({
        ts: Date.now(),
        raf: lastRafAt,
        visibility: document.visibilityState,
        focus: document.hasFocus(),
        seq: sequence,
      });
      sendWindowCommand('app.heartbeat', JSON.parse(payload) as Record<string, unknown>);
    }, intervalMs);

    const cleanup = () => {
      if (rafId !== null) {
        cancelAnimationFrame(rafId);
        rafId = null;
      }
      if (intervalId !== null) {
        window.clearInterval(intervalId);
        intervalId = null;
      }
    };

    // Explicitly cleanup timers on navigation/unload (best effort; helpful for long-running JCEF contexts).
    window.addEventListener('beforeunload', cleanup, { once: true });
    window.addEventListener('pagehide', cleanup, { once: true });

    // Cleanup on Vite HMR (dev only).
    if (import.meta.hot) {
      import.meta.hot.dispose(() => cleanup());
    }

    debugLog('[Main] Bridge heartbeat enabled');
  };
}

const startBridgeHeartbeat = createBridgeHeartbeatStarter();


/**
 * Apply IDEA editor font configuration to CSS variables
 */

let latestEditorFontConfig: {
  fontFamily: string;
  fontSize: number;
  lineSpacing: number;
  fallbackFonts?: string[];
} | null = null;

let latestUiFontConfig: UiFontConfig | null = null;
let latestCodeFontConfig: CodeFontConfig | null = null;

const UI_FONT_STYLE_ELEMENT_ID = 'cc-gui-ui-font-face-style';
const CODE_FONT_STYLE_ELEMENT_ID = 'cc-gui-code-font-face-style';

function escapeCssFontName(name: string): string {
  return name.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

function buildFontFamilyValue(
  config: { fontFamily: string; fallbackFonts?: string[] },
  options: { appendMonospaceFallback?: boolean; appendSansSerifFallback?: boolean } = {
    appendMonospaceFallback: true,
  },
) {
  const fontParts: string[] = [`'${escapeCssFontName(config.fontFamily)}'`];

  if (config.fallbackFonts && config.fallbackFonts.length > 0) {
    for (const fallback of config.fallbackFonts) {
      fontParts.push(`'${escapeCssFontName(fallback)}'`);
    }
  }

  if (options.appendSansSerifFallback) {
    // UI fonts fall back to a sans-serif stack so a failed custom-font load lands on a
    // sensible UI font instead of the browser default serif.
    fontParts.push(
      "'Inter'",
      '-apple-system',
      'BlinkMacSystemFont',
      "'Segoe WPC'",
      "'Segoe UI'",
      "'Microsoft YaHei UI'",
      "'Microsoft YaHei'",
      "'PingFang SC'",
      "'Hiragino Sans GB'",
      "'Noto Sans CJK SC'",
      "'Noto Sans SC'",
      'system-ui',
      'sans-serif',
    );
  } else if (options.appendMonospaceFallback !== false) {
    fontParts.push("'Consolas'", "'Menlo'", "'Monaco'", "'Courier New'", 'monospace');
  }
  return fontParts.join(', ');
}

let currentUiFontBlobUrl: string | null = null;
let currentCodeFontBlobUrl: string | null = null;

function escapeCssUrl(url: string): string {
  return url.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n|\r/g, '');
}

function createFontBlobUrl(base64: string, format: string): string {
  const mimeType = format === 'opentype' ? 'font/opentype' : 'font/truetype';
  const binaryString = atob(base64);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  const blob = new Blob([bytes], { type: mimeType });
  return URL.createObjectURL(blob);
}

function setUiFontFaceStyle(config: UiFontConfig) {
  let styleElement = document.getElementById(UI_FONT_STYLE_ELEMENT_ID) as HTMLStyleElement | null;
  if (!styleElement) {
    styleElement = document.createElement('style');
    styleElement.id = UI_FONT_STYLE_ELEMENT_ID;
    document.head.appendChild(styleElement);
  }

  // Revoke previous blob URL to free memory
  if (currentUiFontBlobUrl) {
    URL.revokeObjectURL(currentUiFontBlobUrl);
    currentUiFontBlobUrl = null;
  }

  if (!config.fontUrl && (!config.fontBase64 || !config.fontFormat)) {
    styleElement.textContent = '';
    return;
  }

  const fontFormat = config.fontFormat || 'truetype';
  let fontSourceUrl = config.fontUrl;
  if (!fontSourceUrl && config.fontBase64) {
    fontSourceUrl = createFontBlobUrl(config.fontBase64, fontFormat);
    currentUiFontBlobUrl = fontSourceUrl;
  }

  const familyName = escapeCssFontName(config.fontFamily);
  styleElement.textContent =
    `@font-face { font-family: '${familyName}'; font-style: normal; font-weight: 100 900;` +
    ` font-display: swap; src: url("${escapeCssUrl(fontSourceUrl || '')}") format('${fontFormat}'); }`;
}

function setCodeFontFaceStyle(config: CodeFontConfig) {
  let styleElement = document.getElementById(CODE_FONT_STYLE_ELEMENT_ID) as HTMLStyleElement | null;
  if (!styleElement) {
    styleElement = document.createElement('style');
    styleElement.id = CODE_FONT_STYLE_ELEMENT_ID;
    document.head.appendChild(styleElement);
  }

  if (currentCodeFontBlobUrl) {
    URL.revokeObjectURL(currentCodeFontBlobUrl);
    currentCodeFontBlobUrl = null;
  }

  if (!config.fontUrl && (!config.fontBase64 || !config.fontFormat)) {
    styleElement.textContent = '';
    return;
  }

  const fontFormat = config.fontFormat || 'truetype';
  let fontSourceUrl = config.fontUrl;
  if (!fontSourceUrl && config.fontBase64) {
    fontSourceUrl = createFontBlobUrl(config.fontBase64, fontFormat);
    currentCodeFontBlobUrl = fontSourceUrl;
  }

  const familyName = escapeCssFontName(config.fontFamily);
  styleElement.textContent =
    `@font-face { font-family: '${familyName}'; font-style: normal; font-weight: 100 900;` +
    ` font-display: swap; src: url("${escapeCssUrl(fontSourceUrl || '')}") format('${fontFormat}'); }`;
}

/**
 * Probe local font availability and log font diagnostics.
 * Evaluates whether configured fonts or system fallbacks are actually available
 * in the current browser/OS environment (e.g. UOS 20 / Debian 10).
 */
function probeAndLogFontDiagnostics(contextTag: string) {
  try {
    const probeCandidates = [
      latestUiFontConfig?.fontFamily,
      latestCodeFontConfig?.fontFamily,
      latestEditorFontConfig?.fontFamily,
      'Noto Sans CJK SC',
      'Noto Sans SC',
      'Source Han Sans SC',
      'WenQuanYi Micro Hei',
      'Droid Sans Fallback',
      'SimSun',
      'Microsoft YaHei UI',
      'Microsoft YaHei',
      'PingFang SC',
      'Hiragino Sans GB',
      '-apple-system',
      'BlinkMacSystemFont',
      'Segoe UI',
      'Inter',
      'system-ui',
      'sans-serif',
      'monospace',
    ].filter((f): f is string => Boolean(f && typeof f === 'string'));

    const checkedStatus: Record<string, boolean> = {};
    for (const fontName of probeCandidates) {
      if (checkedStatus[fontName] !== undefined) continue;
      try {
        checkedStatus[fontName] = document.fonts ? document.fonts.check(`16px "${fontName}"`) : false;
      } catch {
        checkedStatus[fontName] = false;
      }
    }

    const rootStyle = getComputedStyle(document.documentElement);
    const uiFontVal = rootStyle.getPropertyValue('--cc-gui-ui-font-family').trim();
    const codeFontVal = rootStyle.getPropertyValue('--cc-gui-code-font-family').trim();

    cardDebugLog(`[FontDiagnostic:${contextTag}] Font Environment Diagnostic:`, {
      latestUiConfig: latestUiFontConfig,
      latestCodeConfig: latestCodeFontConfig,
      latestEditorConfig: latestEditorFontConfig,
      computedCssVars: {
        '--cc-gui-ui-font-family': uiFontVal,
        '--cc-gui-code-font-family': codeFontVal,
      },
      availabilityChecks: checkedStatus,
    });
  } catch (err) {
    cardDebugLog(`[FontDiagnostic:${contextTag}] probe error:`, String(err));
  }
}

function syncFontFamilies() {
  const root = document.documentElement;
  if (latestUiFontConfig) {
    root.style.setProperty('--cc-gui-ui-font-family', buildFontFamilyValue({
      fontFamily: latestUiFontConfig.fontFamily,
      fallbackFonts: latestUiFontConfig.fallbackFonts,
    }, { appendMonospaceFallback: false, appendSansSerifFallback: true }));
  }

  const codeSourceConfig = latestCodeFontConfig || latestEditorFontConfig;
  if (codeSourceConfig) {
    const codeFontFamilyValue = buildFontFamilyValue({
      fontFamily: codeSourceConfig.fontFamily,
      fallbackFonts: codeSourceConfig.fallbackFonts ?? latestEditorFontConfig?.fallbackFonts,
    });
    root.style.setProperty('--cc-gui-code-font-family', codeFontFamilyValue);
    // Keep legacy variable in sync so existing components continue to pick up the effective code font.
    root.style.setProperty('--idea-editor-font-family', codeFontFamilyValue);
  }

  probeAndLogFontDiagnostics('sync');
}

function applyEditorTypographyConfig(config: {
  fontFamily: string;
  fontSize: number;
  lineSpacing: number;
  fallbackFonts?: string[];
}) {
  const root = document.documentElement;
  latestEditorFontConfig = config;
  root.style.setProperty('--cc-gui-editor-font-family', buildFontFamilyValue(config));
  root.style.setProperty('--idea-editor-font-size', `${config.fontSize}px`);
  root.style.setProperty('--idea-editor-line-spacing', String(config.lineSpacing));
  syncFontFamilies();
}

function applyUiFontConfig(config: UiFontConfig | string) {
  const normalizedConfig: UiFontConfig =
    typeof config === 'string' ? JSON.parse(config) as UiFontConfig : config;

  latestUiFontConfig = normalizedConfig;
  setUiFontFaceStyle(normalizedConfig);
  syncFontFamilies();
}

function applyCodeFontConfig(config: CodeFontConfig | string) {
  const normalizedConfig: CodeFontConfig =
    typeof config === 'string' ? JSON.parse(config) as CodeFontConfig : config;

  latestCodeFontConfig = normalizedConfig;
  setCodeFontFaceStyle(normalizedConfig);
  syncFontFamilies();
}

// Register the font and language apply handlers in the semantic registry.
// The host addresses these by function name; the catalog maps them to the
// semantic types below.

// Check for pending font config (Java side may execute before JS)
if (window.__pendingFontConfig) {
  debugLog('[Main] Found pending font config, applying...');
  applyEditorTypographyConfig(window.__pendingFontConfig);
  delete window.__pendingFontConfig;
}

if (window.__pendingUiFontConfig) {
  debugLog('[Main] Found pending UI font config, applying...');
  applyUiFontConfig(window.__pendingUiFontConfig);
  delete window.__pendingUiFontConfig;
}

if (window.__pendingCodeFontConfig) {
  debugLog('[Main] Found pending code font config, applying...');
  applyCodeFontConfig(window.__pendingCodeFontConfig);
  delete window.__pendingCodeFontConfig;
}

/**
 * Apply language configuration to i18n
 * Supports both direct objects (startup injection) and JSON strings (bridge callbacks).
 */
function applyLanguageConfig(rawConfig: { language: string; source?: string; ideaLocale?: string } | string) {
  let config: { language: string; source?: string; ideaLocale?: string };

  if (typeof rawConfig === 'string') {
    try {
      config = JSON.parse(rawConfig) as { language: string; source?: string; ideaLocale?: string };
    } catch (error) {
      console.error('[Main] Failed to parse language config:', error, rawConfig);
      return;
    }
  } else {
    config = rawConfig;
  }

  const { language, source } = config;

  // Validate that the language code is supported
  const supportedLanguages = ['zh', 'en', 'zh-TW', 'hi', 'es', 'fr', 'ja', 'ru', 'ko', 'pt-BR'];
  const targetLanguage = supportedLanguages.includes(language) ? language : 'en';

  debugLog('[Main] Applying language config:', config, 'target language:', targetLanguage, 'source:', source);

  const selectionMode = source === 'user' ? 'manual' : 'followIdea';

  i18n.changeLanguage(targetLanguage)
    .then(() => {
      localStorage.setItem('language', targetLanguage);
      localStorage.setItem('languageSelectionMode', selectionMode);
      // Migrate from legacy 'languageManuallySet' key to 'languageSelectionMode'
      localStorage.removeItem('languageManuallySet');
      // Notify subscribers (e.g. AppearanceTab) of the authoritative config so
      // they can resync even when i18n.language did not change.
      window.dispatchEvent(new CustomEvent('language-config-applied', {
        detail: { language: targetLanguage, selectionMode },
      }));
      debugLog('[Main] Applied language:', targetLanguage, 'source:', source ?? 'idea');
    })
    .catch((error) => {
      console.error('[Main] Failed to change language:', error);
    });
}

// Register the applyIdeaLanguageConfig handler in the semantic registry.

registerHostEvents({
  'font.idea': applyEditorTypographyConfig,
  'font.ui.apply': applyUiFontConfig,
  'font.code.apply': applyCodeFontConfig,
  'language.apply': applyLanguageConfig,
  'notification.sound.play': (json: string) => {
    try {
      const payload = JSON.parse(json) as { soundId?: string; variant?: string; customDataBase64?: string };
      playNotificationSound(payload);
    } catch {
      // ignore
    }
  },
  'daemon.status': (payload: HostEventPayload | string) => {
    // Named events carry the payload as `{ data: "<json>" }`; the legacy path
    // passed the JSON string directly. Normalize once here so both
    // useUsageTracking and __pendingDaemonStatus always see a string.
    const json = typeof payload === 'string'
      ? payload
      : typeof payload?.data === 'string'
        ? payload.data
        : JSON.stringify(payload ?? {});
    // Keep the latest payload available for useUsageTracking to drain on mount.
    // CustomEvents dispatched before the listener is attached are lost.
    window.__pendingDaemonStatus = json;
    window.dispatchEvent(new CustomEvent('updateDaemonStatus', { detail: json }));
  },
  'turn.retry': onRetryStatusFromHost,
  'turn.error': onTerminalErrorFromHost,
  'linkify.capabilities': (json: string) => {
    applyLinkifyCapabilitiesPayload(json);
  },
  'tab.activated': () => {
    forceWebviewRepaint('tab-activated');
  },
});

// 通知提示音播放器（全局常驻）：判定在宿主 NotificationService，
// 这里只负责发声。payload: { soundId?, variant?, customDataBase64? }

// Check for pending language config (Java side may execute before JS)
if (window.__pendingLanguageConfig) {
  debugLog('[Main] Found pending language config, applying...');
  applyLanguageConfig(window.__pendingLanguageConfig);
  delete window.__pendingLanguageConfig;
}

// OSR surface publication fence. Java's SurfaceFrameFence drives a tokenized
// A/B raster pulse and refuses to advance its paint gate on the strength of
// executeJavaScript() alone — it waits for the applied-ack these functions send
// back via `surface_damage_applied`. Without this registration the fence cannot
// leave its first phase, so every history render and tab activation burns the
// full OSR_FRAME_FENCE_TIMEOUT_MS (1s) before falling back.
if (typeof window !== 'undefined') {
  window.__ccguiSurfaceDamagePhaseA = beginSurfaceDamagePulse;
  window.__ccguiSurfaceDamagePhaseB = advanceSurfaceDamagePulse;
  window.__ccguiSurfaceDamageReplace = replaceSurfaceDamagePulse;
  window.__ccguiSurfaceDamageFinish = finishSurfaceDamagePulse;
  window.__ccguiSurfaceDamageCancel = cancelSurfaceDamagePulse;
}

// UI preferences (theme / font scale / colors / behaviour toggles) are owned by
// the extension host; register the push callback before React mounts so an early
// `applyUiPreferences` is never dropped.
installUiPreferencesBridge();
installPinnedModelsBridge();

// Render the React application
ReactDOM.createRoot(document.getElementById('app') as HTMLElement).render(
  <ErrorBoundary>
    <UIStateProvider>
      <SessionProvider>
        <MessagesProvider>
          <TaskEventProvider>
            <DialogProvider>
              <App />
            </DialogProvider>
          </TaskEventProvider>
        </MessagesProvider>
      </SessionProvider>
    </UIStateProvider>
  </ErrorBoundary>,
);

// Once the bridge is available, initialize slash commands
waitForBridge(() => {
  debugLog('[Main] Bridge ready, setting up slash commands');
  setupSlashCommandsCallback();
  setupDollarCommandsCallback();
  startBridgeHeartbeat();

  debugLog('[Main] Sending frontend_ready signal');
  sendWindowCommand('app.frontendReady');

  // Authoritative appearance / behaviour settings (host globalState). Sent
  // right after frontend_ready so the host can answer from warm state.
  requestUiPreferences();
  sendWindowCommand('settings.pinnedModels.get');
  sendWindowCommand('settings.font.editor.get');
  sendWindowCommand('settings.font.ui.get');
  sendWindowCommand('settings.font.code.get');

  debugLog('[Main] Sending commands.refresh request');
  const refreshTabId = window.__sessionTabsStore?.getActiveTabId?.();
  if (refreshTabId) {
    sendHostCommand(refreshTabId, 'commands.refresh');
  } else {
    sendWindowCommand('commands.refresh');
  }

  sendWindowCommand('filesystem.linkify.get');
});
