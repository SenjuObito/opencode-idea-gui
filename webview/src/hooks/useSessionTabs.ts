import { useCallback, useEffect, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage } from '../types';
import type { SessionTabData } from '../components/ChatHeader/types';
import { sendBridgeEvent } from '../utils/bridge';

interface UseSessionTabsOptions {
  currentSessionId: string | null;
  sessionTitle: string;
  setCustomSessionTitle: (title: string | null) => void;
  updateHistoryTitle: (sessionId: string, newTitle: string) => void;
  messages: ClaudeMessage[];
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  loading: boolean;
  streamingActive: boolean;
  isShared: boolean;
  forceCreateNewSession: () => void;
  loadHistorySession: (sessionId: string) => void;
  t: TFunction;
}

export function useSessionTabs({
  currentSessionId,
  sessionTitle,
  setCustomSessionTitle,
  updateHistoryTitle,
  messages,
  setMessages,
  loading,
  streamingActive,
  isShared,
  forceCreateNewSession,
  loadHistorySession,
  t,
}: UseSessionTabsOptions) {
  const [tabs, setTabs] = useState<SessionTabData[]>([
    {
      id: 'tab-1',
      sessionId: null,
      title: t('common.newSession', { defaultValue: '新会话' }),
      messages: [],
      isShared: false,
    },
  ]);
  const [activeTabId, setActiveTabId] = useState<string>('tab-1');

  // Keep ref to avoid stale closures in listeners
  const tabsRef = useRef(tabs);
  useEffect(() => {
    tabsRef.current = tabs;
  }, [tabs]);

  const activeTabIdRef = useRef(activeTabId);
  useEffect(() => {
    activeTabIdRef.current = activeTabId;
  }, [activeTabId]);

  // Sync active tab's metadata as conversation progresses
  useEffect(() => {
    setTabs((prev) =>
      prev.map((tab) => {
        if (tab.id === activeTabId) {
          return {
            ...tab,
            sessionId: currentSessionId,
            title: sessionTitle || tab.title,
            isShared,
            messages,
            loading,
            streamingActive,
          };
        }
        return tab;
      })
    );
  }, [activeTabId, currentSessionId, sessionTitle, isShared, messages, loading, streamingActive]);

  // Select tab
  const selectTab = useCallback(
    (targetTabId: string) => {
      if (targetTabId === activeTabIdRef.current) return;

      const currentActive = tabsRef.current.find((t) => t.id === activeTabIdRef.current);
      if (currentActive) {
        currentActive.messages = messages;
        currentActive.sessionId = currentSessionId;
        currentActive.title = sessionTitle;
      }

      const targetTab = tabsRef.current.find((t) => t.id === targetTabId);
      if (!targetTab) return;

      setActiveTabId(targetTabId);

      // Restore messages immediately from memory
      setMessages(targetTab.messages || []);

      if (targetTab.sessionId) {
        if (targetTab.sessionId !== currentSessionId) {
          loadHistorySession(targetTab.sessionId);
        }
      } else {
        forceCreateNewSession();
      }
    },
    [currentSessionId, sessionTitle, messages, setMessages, loadHistorySession, forceCreateNewSession]
  );

  // Create new session tab
  const createTab = useCallback(() => {
    const newId = `tab-${Date.now()}`;
    const newTab: SessionTabData = {
      id: newId,
      sessionId: null,
      title: t('common.newSession', { defaultValue: '新会话' }),
      messages: [],
      isShared: false,
    };

    setTabs((prev) => [
      ...prev.map((t) =>
        t.id === activeTabIdRef.current
          ? { ...t, messages, sessionId: currentSessionId, title: sessionTitle }
          : t
      ),
      newTab,
    ]);
    setActiveTabId(newId);
    setMessages([]);
    forceCreateNewSession();
  }, [messages, currentSessionId, sessionTitle, t, setMessages, forceCreateNewSession]);

  // Close tab
  const closeTab = useCallback(
    (tabId: string) => {
      setTabs((prev) => {
        if (prev.length <= 1) {
          const freshTab: SessionTabData = {
            id: `tab-${Date.now()}`,
            sessionId: null,
            title: t('common.newSession', { defaultValue: '新会话' }),
            messages: [],
            isShared: false,
          };
          setActiveTabId(freshTab.id);
          setMessages([]);
          forceCreateNewSession();
          return [freshTab];
        }

        const nextTabs = prev.filter((t) => t.id !== tabId);
        if (tabId === activeTabIdRef.current) {
          const closedIndex = prev.findIndex((t) => t.id === tabId);
          const nextActive = nextTabs[Math.max(0, closedIndex - 1)];
          if (nextActive) {
            setActiveTabId(nextActive.id);
            setMessages(nextActive.messages || []);
            if (nextActive.sessionId) {
              loadHistorySession(nextActive.sessionId);
            } else {
              forceCreateNewSession();
            }
          }
        }
        return nextTabs;
      });
    },
    [t, setMessages, forceCreateNewSession, loadHistorySession]
  );

  // Rename tab
  const renameTab = useCallback(
    (tabId: string, newTitle: string) => {
      setTabs((prev) =>
        prev.map((t) => (t.id === tabId ? { ...t, title: newTitle, isCustomTitle: true } : t))
      );
      if (tabId === activeTabIdRef.current) {
        setCustomSessionTitle(newTitle);
        if (currentSessionId) {
          updateHistoryTitle(currentSessionId, newTitle);
        }
      } else {
        const target = tabsRef.current.find((t) => t.id === tabId);
        if (target?.sessionId) {
          updateHistoryTitle(target.sessionId, newTitle);
        }
      }
    },
    [currentSessionId, setCustomSessionTitle, updateHistoryTitle]
  );

  // Fork tab
  const forkTab = useCallback(
    (tab: SessionTabData) => {
      if (tab.id !== activeTabIdRef.current) {
        selectTab(tab.id);
      }
      if (tab.sessionId) {
        sendBridgeEvent('fork_session', 'full');
      }
    },
    [selectTab]
  );

  // Expose window.createSessionTab for IDEA title bar action (+)
  useEffect(() => {
    (window as any).createSessionTab = createTab;
    return () => {
      delete (window as any).createSessionTab;
    };
  }, [createTab]);

  return {
    tabs,
    activeTabId,
    selectTab,
    createTab,
    closeTab,
    renameTab,
    forkTab,
  };
}
