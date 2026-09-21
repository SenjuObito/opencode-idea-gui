import { describe, expect, it, beforeEach } from 'vitest';
import { applyFontScale } from './fontScale';

describe('applyFontScale', () => {
  beforeEach(() => {
    document.body.innerHTML = '<div id="app"></div>';
    document.documentElement.style.removeProperty('--font-scale');
  });

  it('sets the --font-scale variable on the document element', () => {
    applyFontScale('1.4');
    expect(document.documentElement.style.getPropertyValue('--font-scale')).toBe('1.4');
  });

  it('sets inline zoom on #app so JCEF / Chromium applies zoom scale reliably', () => {
    const app = document.getElementById('app') as HTMLElement;
    applyFontScale('1.4');
    expect(app.style.zoom).toBe('1.4');
  });

  it('updates inline zoom on #app when scale changes', () => {
    const app = document.getElementById('app') as HTMLElement;
    applyFontScale('0.8');
    expect(app.style.zoom).toBe('0.8');
    expect(document.documentElement.style.getPropertyValue('--font-scale')).toBe('0.8');
  });
});
