/**
 * agentCallbacks.ts
 *
 * Registers window bridge callbacks for selection context:
 * addSelectionInfo, addCodeSnippet, clearSelectionInfo.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { registerHostEvents } from '../../../utils/hostEventRegistry';

export function registerAgentAndSelectionCallbacks(options: UseWindowCallbacksOptions): void {
  const {
  setContextInfo,
} = options;

  const addSelectionInfo = (selectionInfo?: string) => {
    if (selectionInfo) {
      const match = selectionInfo.match(/^@([^#]+)(?:#L(\d+)(?:-(\d+))?)?$/);
      if (match) {
        const file = match[1];
        const startLine = match[2] ? parseInt(match[2], 10) : undefined;
        const endLine =
          match[3] ? parseInt(match[3], 10) : startLine !== undefined ? startLine : undefined;
        setContextInfo({
          file,
          startLine,
          endLine,
          raw: selectionInfo,
        });
      }
    }
  };

  const addCodeSnippet = (selectionInfo?: string) => {
    if (selectionInfo && window.insertCodeSnippetAtCursor) {
      window.insertCodeSnippetAtCursor(selectionInfo);
    }
  };

  const clearSelectionInfo = () => {
    setContextInfo(null);
  };

  window.addSelectionInfo = addSelectionInfo;
  window.addCodeSnippet = addCodeSnippet;
  window.clearSelectionInfo = clearSelectionInfo;

  registerHostEvents({
    'selection.info': addSelectionInfo,
    'selection.snippet': addCodeSnippet,
    'selection.clear': clearSelectionInfo,
  });
}
