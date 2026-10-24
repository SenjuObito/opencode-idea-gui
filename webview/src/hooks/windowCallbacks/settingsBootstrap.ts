/**
 * settingsBootstrap.ts
 *
 * Handles initial configuration requests sent to the Java backend and the
 * processing of any values that arrived before the callbacks were registered
 * (stored in window.__pending* slots by main.tsx).
 */

import { sendWindowCommand } from '../../utils/bridge';
import { hostEventHandler } from '../../utils/hostEventRegistry';

const MAX_RETRIES = 30;

/**
 * Fire the three settings queries to the backend.  Retries up to MAX_RETRIES
 * times (at 100 ms intervals) if window.sendToJava is not yet available.
 */
export const startInitialSettingsRequest = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  let settingsRetryCount = 0;
  const requestInitialSettings = () => {
    if (typeof window === 'undefined') {
      return;
    }
    if (window.sendToJava) {
      sendWindowCommand('get_send_shortcut');
      sendWindowCommand('get_auto_open_file_enabled');
      sendWindowCommand('get_permission_dialog_timeout');
    } else {
      settingsRetryCount++;
      if (settingsRetryCount < MAX_RETRIES) {
        setTimeout(requestInitialSettings, 100);
      }
    }
  };
  setTimeout(requestInitialSettings, 200);
};

/**
 * Request the current permission mode from the backend.
 */
export const startModeRequest = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  let modeRetryCount = 0;
  const requestMode = () => {
    if (typeof window === 'undefined') {
      return;
    }
    if (window.sendToJava) {
      sendWindowCommand('settings.mode.get');
    } else {
      modeRetryCount++;
      if (modeRetryCount < MAX_RETRIES) {
        setTimeout(requestMode, 100);
      }
    }
  };
  setTimeout(requestMode, 200);
};

/**
 * Drain any pending window.__pending* values captured by main.tsx before
 * the React callbacks were registered.  Must be called after the corresponding
 * window.updateXxx / window.onXxx callbacks have been assigned.
 */
export const drainPendingSettings = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  const w = window as unknown as Record<string, unknown>;

  if (w.__pendingSendShortcut) {
    const pending = w.__pendingSendShortcut as string;
    delete w.__pendingSendShortcut;
    hostEventHandler('settings.send.shortcut')?.(pending);
  }

  if (w.__pendingAutoOpenFileEnabled) {
    const pending = w.__pendingAutoOpenFileEnabled as string;
    delete w.__pendingAutoOpenFileEnabled;
    hostEventHandler('settings.auto.open.file')?.(pending);
  }

  if (w.__pendingPermissionDialogTimeout) {
    const pending = w.__pendingPermissionDialogTimeout as string;
    delete w.__pendingPermissionDialogTimeout;
    hostEventHandler('settings.permission.timeout')?.(pending);
  }

  if (w.__pendingModeReceived) {
    const pending = w.__pendingModeReceived as string;
    delete w.__pendingModeReceived;
    hostEventHandler('settings.mode.received')?.(pending);
  }
};
