/**
 * settingsBootstrap.ts
 *
 * Handles initial configuration requests sent to the Java backend. Early host
 * events are no longer buffered here: the host dispatcher queues an event whose
 * handler is not registered yet and flushes it in order once it is.
 */

import { sendWindowCommand } from '../../utils/bridge';

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
