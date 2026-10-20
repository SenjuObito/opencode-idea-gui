/**
 * sessionCallbacks.ts
 *
 * Registers window bridge callbacks for session management,
 * setSessionId, addToast, onExportSessionData.
 */

import type { MutableRefObject } from 'react';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { downloadJSON } from '../../../utils/exportMarkdown';
import { releaseSessionTransition } from '../sessionTransition';
import { sendBridgeEvent, cardDebugLog } from '../../../utils/bridge';

// Matches session-titles-service.cjs#updateTitle, which rejects longer titles.
const CUSTOM_TITLE_MAX_LENGTH = 50;

export function registerSessionCallbacks(
  options: UseWindowCallbacksOptions,
  tRef: MutableRefObject<UseWindowCallbacksOptions['t']>,
): void {
  const {
    addToast,
    setCurrentSessionId,
    customSessionTitleRef,
    currentSessionIdRef,
    updateHistoryTitle,
    applyHistoryTitleLocal,
    setCustomSessionTitle,
  } = options;

  window.setSessionId = (sessionId: string, previousSessionId?: string) => {
    if (!sessionId) return;
    const oldId = currentSessionIdRef.current;
    cardDebugLog('[setSessionId] called, sessionId:', sessionId, 'previousSessionId:', previousSessionId, 'oldId:', oldId, 'transitioning:', window.__sessionTransitioning);

    const tabsStore = window.__sessionTabsStore;
    const tabs = tabsStore?.getTabs?.() || [];
    const activeTabId = tabsStore?.getActiveTabId?.();
    const activeTab = tabs.find((t) => t.id === activeTabId);

    // `previousSessionId` is the adapter's prior identity, which is a tab id
    // only for a session that was created from a tab (`createSessionForTab`).
    // For history loads and repeated [SESSION_ID] markers it is the same real
    // session id, and for the startup adapter it is empty. Never treat it as
    // "the tab to switch to".
    let targetTab = previousSessionId
      ? tabs.find((t) => t.id === previousSessionId || t.sessionId === previousSessionId)
      : null;
    if (!targetTab) {
      targetTab = tabs.find((t) => t.sessionId === sessionId);
    }
    if (!targetTab && previousSessionId === '' && activeTab && !activeTab.sessionId) {
      // Brand-new session resolving for the first time while nothing claimed
      // the draft tab yet. This is the only case where the active tab may be
      // adopted; every other unmatched notification is dropped.
      targetTab = activeTab;
    }

    const syncTitle = () => {
      const title = customSessionTitleRef.current;
      if (title && oldId !== sessionId) {
        if (title.length <= CUSTOM_TITLE_MAX_LENGTH) {
          updateHistoryTitle(sessionId, title);
        } else {
          applyHistoryTitleLocal(sessionId, title);
        }
      }
    };

    if (!targetTab) {
      cardDebugLog(
        '[setSessionId] no tab claims this identity; dropping binding',
        'sessionId:', sessionId, 'previousSessionId:', previousSessionId, 'activeTabId:', activeTabId,
      );
      return;
    }

    const resolvedTabId = targetTab.id;
    tabsStore?.updateTabSessionId?.(resolvedTabId, sessionId);

    if (resolvedTabId !== activeTabId) {
      // Background session: rebind its tab only. Changing currentSessionIdRef
      // here would make its deltas look foreground and corrupt the visible tab.
      return;
    }

    currentSessionIdRef.current = sessionId;
    setCurrentSessionId(sessionId);
    syncTitle();
    // Release a pending session transition only when this is NOT a history
    // load (those carry a transition target and must wait for
    // historyLoadComplete). The new-session flow has no target and relies on
    // this release so the first deltas are accepted.
    if (!window.__sessionTransitionTargetSessionId) {
      releaseSessionTransition();
    }
  };

  window.addToast = (message, type) => {
    addToast(message, type as 'info' | 'success' | 'warning' | 'error' | undefined);
  };

  window.onExportSessionData = (json) => {
    try {
      const data = JSON.parse(json);
      if (data.sessionId && data.messages) {
        const exportContent = JSON.stringify(data, null, 2);
        const sanitizedTitle = (data.title || 'session')
          .replace(/[<>:"/\\|?*]/g, '_')
          .replace(/\s+/g, '_')
          .substring(0, 50);
        const filename = `${sanitizedTitle}_${data.sessionId.substring(0, 8)}.json`;
        downloadJSON(exportContent, filename);
      } else if (data.error) {
        addToast(data.error, 'error');
      } else {
        addToast(tRef.current('history.exportFailed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to process export data:', error);
      addToast(tRef.current('history.exportFailed'), 'error');
    }
  };

  // =========================================================================
  // AI Title Callback
  // =========================================================================

  window.updateSessionTitle = (sessionId: string, title: string) => {
    if (!title || !title.trim() || !sessionId) return;
    const cleanTitle = title.trim();
    // Only the session that is actually on screen may drive the header title.
    // An empty currentSessionId means the visible tab is still unbound (a new
    // draft tab) and must not inherit a title generated for another session.
    if (currentSessionIdRef.current === sessionId) {
      setCustomSessionTitle(cleanTitle);
    }
    window.__sessionTabsStore?.updateTabTitle?.(sessionId, cleanTitle);
    applyHistoryTitleLocal(sessionId, cleanTitle);
  };

  // =========================================================================
  // SDK-to-CLI Session Conversion Result Callback
  // =========================================================================

  window.onConversionResult = (json: string) => {
    const reloadHistory = () => {
      const provider = options.currentProviderRef.current;
      if (provider) {
        sendBridgeEvent('deep_search_history', provider);
      } else {
        cardDebugLog('[Frontend] Provider unavailable for conversion state reload');
      }
    };

    try {
      const result = JSON.parse(json);
      if (result.success) {
        if (result.infoCode === 'ALREADY_CLI_SESSION') {
          addToast(tRef.current('history.conversionErrors.ALREADY_CLI_SESSION'), 'info');
        } else {
          addToast(tRef.current('history.convertSuccess'), 'success');
          reloadHistory();
        }
        return;
      }

      const errorCode = result.errorCode;
      let errorMessage = tRef.current('history.convertFailed');

      if (errorCode && tRef.current(`history.conversionErrors.${errorCode}`)) {
        errorMessage = tRef.current(`history.conversionErrors.${errorCode}`);
      }

      addToast(errorMessage, 'error');
      reloadHistory();
    } catch (error) {
      console.error('[Frontend] Failed to parse conversion result:', error);
      addToast(tRef.current('history.convertFailed'), 'error');
      reloadHistory();
    }
  };
}
