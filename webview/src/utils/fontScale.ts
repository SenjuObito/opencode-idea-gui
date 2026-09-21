/**
 * Font scaling — single entry point for applying the UI font scale.
 *
 * The scale lives in the `--font-scale` CSS variable on <html>; base.less
 * derives `#app` zoom and its inverse vw/vh size from it. Keeping every writer
 * on this helper guarantees the variable and any legacy inline `zoom` residue
 * on #app never disagree (a stale inline zoom overrides the stylesheet and
 * leaves the UI scaled without filling the viewport).
 */

/** Apply a font scale (e.g. '0.9', '1.4') to the whole app. */
export function applyFontScale(scale: string): void {
  document.documentElement.style.setProperty('--font-scale', scale);
  const app = document.getElementById('app');
  if (app) {
    app.style.zoom = scale;
  }
}

