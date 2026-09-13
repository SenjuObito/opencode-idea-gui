/**
 * Persistence for the "compact session" confirm dialog preference.
 *
 * Storage: localStorage (per-machine, resets when switching devices — matches user expectation).
 *
 * Sync: a CustomEvent is dispatched after a successful write so that any open
 * settings page (or any other listener) can react in real time. This mirrors
 * the existing pattern used by `skipNewSessionConfirm` (see utils/skipNewSessionConfirm.ts).
 *
 * Two semantically-equivalent API pairs are exported:
 *   - getSkipCompactConfirm / setSkipCompactConfirm  (raw, "skip" semantics)
 *   - isCompactConfirmEnabled / setCompactConfirmEnabled (inverse, positive semantics)
 *
 * UI code should prefer the positive-semantics pair to avoid double negatives.
 * Internal call sites that gate "should we bypass the dialog?" stay with the raw pair.
 */

import { getUiPreferences, updateUiPreferences } from './uiPreferences';

export const SKIP_COMPACT_CONFIRM_KEY = 'skipCompactConfirm';
export const SKIP_COMPACT_CONFIRM_EVENT = 'skipCompactConfirmChanged';

export interface SkipCompactConfirmChangedDetail {
  enabled: boolean;
}

/**
 * Read the current preference. Defaults to `false` (i.e. keep showing the dialog)
 * so existing users see no behaviour change after upgrade.
 */
export function getSkipCompactConfirm(): boolean {
  try {
    const fromPrefs = getUiPreferences().skipCompactConfirm;
    if (fromPrefs) return true;
    return localStorage.getItem(SKIP_COMPACT_CONFIRM_KEY) === 'true';
  } catch {
    return false;
  }
}

/**
 * Persist the preference AND notify any listeners in the same tab.
 */
export function setSkipCompactConfirm(value: boolean): void {
  try {
    localStorage.setItem(SKIP_COMPACT_CONFIRM_KEY, value ? 'true' : 'false');
    updateUiPreferences({ skipCompactConfirm: value });
  } catch (error) {
    console.warn('[skipCompactConfirm] failed to persist:', error);
    return;
  }

  const detail: SkipCompactConfirmChangedDetail = { enabled: value };
  window.dispatchEvent(new CustomEvent(SKIP_COMPACT_CONFIRM_EVENT, { detail }));
}

/**
 * Positive-semantics read: is the confirm dialog currently enabled (i.e. will it show)?
 *
 * Prefer this in UI code so that the toggle's "checked" state maps 1:1 to the
 * user-facing label without inversions.
 */
export function isCompactConfirmEnabled(): boolean {
  return !getSkipCompactConfirm();
}

/**
 * Positive-semantics write: enable (true) or disable (false) the confirm dialog.
 *
 * Internally inverts to the stored "skip" flag and delegates to
 * `setSkipCompactConfirm`, which handles persistence + event dispatch.
 */
export function setCompactConfirmEnabled(enabled: boolean): void {
  setSkipCompactConfirm(!enabled);
}
