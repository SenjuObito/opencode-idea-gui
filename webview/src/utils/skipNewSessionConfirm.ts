/**
 * Persistence for the "create new session with existing messages" confirm dialog
 * preference.
 *
 * Storage: localStorage (per-machine, resets when switching devices — matches user expectation).
 *
 * Sync: a CustomEvent is dispatched after a successful write so that any open
 * settings page (or any other listener) can react in real time. This mirrors
 * the existing pattern used by `historyCompletionEnabled` (see settings/index.tsx).
 *
 * IMPORTANT: This preference deliberately does NOT cover the "interrupt running AI"
 * confirm dialog — interrupting an in-progress AI response is a more dangerous
 * operation and must always require explicit confirmation. See AppDialogs.tsx.
 *
 * Two semantically-equivalent API pairs are exported:
 *   - getSkipNewSessionConfirm / setSkipNewSessionConfirm  (raw, "skip" semantics)
 *   - isNewSessionConfirmEnabled / setNewSessionConfirmEnabled (inverse, positive semantics)
 *
 * UI code should prefer the positive-semantics pair to avoid double negatives.
 * Internal call sites that gate "should we bypass the dialog?" stay with the raw pair.
 */

import { getUiPreferences, updateUiPreferences } from './uiPreferences';

export const SKIP_NEW_SESSION_CONFIRM_KEY = 'skipNewSessionConfirm';
export const SKIP_NEW_SESSION_CONFIRM_EVENT = 'skipNewSessionConfirmChanged';

export interface SkipNewSessionConfirmChangedDetail {
  enabled: boolean;
}

/**
 * Read the current preference. Defaults to `false` (i.e. keep showing the dialog)
 * so existing users see no behaviour change after upgrade.
 */
export function getSkipNewSessionConfirm(): boolean {
  try {
    const fromPrefs = getUiPreferences().skipNewSessionConfirm;
    if (fromPrefs) return true;
    return localStorage.getItem(SKIP_NEW_SESSION_CONFIRM_KEY) === 'true';
  } catch {
    return false;
  }
}

/**
 * Persist the preference AND notify any listeners in the same tab.
 */
export function setSkipNewSessionConfirm(value: boolean): void {
  try {
    localStorage.setItem(SKIP_NEW_SESSION_CONFIRM_KEY, value ? 'true' : 'false');
    updateUiPreferences({ skipNewSessionConfirm: value });
  } catch (error) {
    console.warn('[skipNewSessionConfirm] failed to persist:', error);
    return;
  }

  const detail: SkipNewSessionConfirmChangedDetail = { enabled: value };
  window.dispatchEvent(new CustomEvent(SKIP_NEW_SESSION_CONFIRM_EVENT, { detail }));
}

/**
 * Positive-semantics read: is the confirm dialog currently enabled (i.e. will it show)?
 *
 * Prefer this in UI code so that the toggle's "checked" state maps 1:1 to the
 * user-facing label without inversions.
 */
export function isNewSessionConfirmEnabled(): boolean {
  return !getSkipNewSessionConfirm();
}

/**
 * Positive-semantics write: enable (true) or disable (false) the confirm dialog.
 *
 * Internally inverts to the stored "skip" flag and delegates to
 * `setSkipNewSessionConfirm`, which handles persistence + event dispatch.
 */
export function setNewSessionConfirmEnabled(enabled: boolean): void {
  setSkipNewSessionConfirm(!enabled);
}
