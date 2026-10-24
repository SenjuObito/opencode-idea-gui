import { useEffect } from 'react';
import { sendWindowCommand } from '../utils/bridge';
import { registerHostEvents, unregisterHostEvents } from '../utils/hostEventRegistry';
import { insertNewlineAtCursor } from './useContextMenu';

/**
 * Registers IDEA shortcut action handler (copy/cut/send/newline from Java-registered Actions).
 */
export function useContextActions() {
  useEffect(() => {
    const execContextAction = (action: string) => {
      switch (action) {
        case 'copy': {
          const activeEl = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
          let text = '';
          if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
            text = activeEl.value.substring(activeEl.selectionStart ?? 0, activeEl.selectionEnd ?? 0);
          } else {
            text = window.getSelection()?.toString() ?? '';
          }
          if (text) {
            sendWindowCommand('clipboard.write', { text });
          }
          break;
        }
        case 'cut': {
          const activeEl = document.activeElement as HTMLInputElement | HTMLTextAreaElement | null;
          let text = '';
          if (activeEl && (activeEl.tagName === 'INPUT' || activeEl.tagName === 'TEXTAREA')) {
            const start = activeEl.selectionStart ?? 0;
            const end = activeEl.selectionEnd ?? 0;
            text = activeEl.value.substring(start, end);
            if (text) {
              activeEl.setRangeText('', start, end, 'end');
              activeEl.dispatchEvent(new Event('input', { bubbles: true }));
            }
          } else {
            text = window.getSelection()?.toString() ?? '';
            if (text) {
              document.execCommand('delete');
            }
          }
          if (text) {
            sendWindowCommand('clipboard.write', { text });
          }
          break;
        }
        case 'send': {
          document.dispatchEvent(new CustomEvent('ideaSend'));
          break;
        }
        case 'newline': {
          const activeEl = document.activeElement;
          if (activeEl && activeEl.getAttribute('contenteditable') === 'true') {
            insertNewlineAtCursor();
          }
          break;
        }
      }
    };

    registerHostEvents({ 'internal.chat.contextAction': execContextAction });

    return () => {
      unregisterHostEvents(['internal.chat.contextAction']);
    };
  }, []);
}
