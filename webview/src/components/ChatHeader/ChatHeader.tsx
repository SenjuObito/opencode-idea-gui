import { useEffect, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { BackIcon } from '../Icons';
import type { SessionTabData } from './types';
import { SessionTabBar } from './SessionTabBar';

export interface ChatHeaderProps {
  currentView: 'chat' | 'history' | 'settings';
  t: TFunction;
  tabs: SessionTabData[];
  activeTabId: string;
  onSelectTab: (tabId: string) => void;
  onCloseTab: (tabId: string) => void;
  onRenameTab: (tabId: string, newTitle: string) => void;
  onForkTab: (tab: SessionTabData) => void;
  onShareTab: (tab: SessionTabData) => void;
  onCopyShareLinkTab: (tab: SessionTabData) => void;
  onUnshareTab: (tab: SessionTabData) => void;
  onBack: () => void;
  onNewSession: () => void;
  onHistory: () => void;
  onSettings: () => void;
  onOpenSearch?: () => void;
  onExport?: () => void;
}

export function ChatHeader({
  currentView,
  t,
  tabs,
  activeTabId,
  onSelectTab,
  onCloseTab,
  onRenameTab,
  onForkTab,
  onShareTab,
  onCopyShareLinkTab,
  onUnshareTab,
  onBack,
  onNewSession,
  onHistory,
  onSettings,
  onOpenSearch,
  onExport,
}: ChatHeaderProps): React.ReactElement | null {
  const [moreMenuOpen, setMoreMenuOpen] = useState(false);
  const moreMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!moreMenuOpen) return;
    const handleMouseDown = (e: MouseEvent) => {
      if (moreMenuRef.current && !moreMenuRef.current.contains(e.target as Node)) {
        setMoreMenuOpen(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMoreMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleMouseDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleMouseDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [moreMenuOpen]);

  if (currentView === 'settings') {
    return null;
  }

  return (
    <div className="header">
      <div className="header-left">
        {currentView === 'history' ? (
          <button className="back-button" onClick={onBack} data-tooltip={t('common.back')}>
            <BackIcon /> {t('common.back')}
          </button>
        ) : (
          <SessionTabBar
            tabs={tabs}
            activeTabId={activeTabId}
            onSelectTab={onSelectTab}
            onCloseTab={onCloseTab}
            onRenameTab={onRenameTab}
            onForkTab={onForkTab}
            onShareTab={onShareTab}
            onCopyShareLinkTab={onCopyShareLinkTab}
            onUnshareTab={onUnshareTab}
            t={t}
          />
        )}
      </div>

      <div className="header-right">
        {currentView === 'chat' && (
          <>
            <button
              className="icon-button"
              onClick={onNewSession}
              data-tooltip={t('common.newSession', { defaultValue: 'New Chat' })}
              aria-label={t('common.newSession', { defaultValue: 'New Chat' })}
            >
              <span className="codicon codicon-plus" />
            </button>

            {onOpenSearch && (
              <button
                className="icon-button"
                onClick={onOpenSearch}
                data-tooltip={t('chat.search.openTooltip', { defaultValue: 'Search in conversation' })}
                aria-label={t('chat.search.openTooltip', { defaultValue: 'Search in conversation' })}
              >
                <span className="codicon codicon-search" />
              </button>
            )}

            <button
              className="icon-button"
              onClick={onHistory}
              data-tooltip={t('common.history', { defaultValue: 'History' })}
              aria-label={t('common.history', { defaultValue: 'History' })}
            >
              <span className="codicon codicon-history" />
            </button>

            <div className="header-more-menu-wrapper" ref={moreMenuRef}>
              <button
                className={`icon-button ${moreMenuOpen ? 'active' : ''}`}
                onClick={() => setMoreMenuOpen((prev) => !prev)}
                data-tooltip={t('common.more', { defaultValue: 'More options' })}
                aria-label={t('common.more', { defaultValue: 'More options' })}
                aria-haspopup="menu"
                aria-expanded={moreMenuOpen}
              >
                <span className="codicon codicon-ellipsis" />
              </button>
              {moreMenuOpen && (
                <div className="menu-dropdown header-dropdown-menu" role="menu">
                  {onExport && (
                    <button
                      className="menu-item"
                      role="menuitem"
                      onClick={() => {
                        setMoreMenuOpen(false);
                        onExport();
                      }}
                    >
                      <span className="codicon codicon-markdown" />
                      <span>{t('chat.exportMarkdown', { defaultValue: 'Export as Markdown' })}</span>
                    </button>
                  )}
                  <button
                    className="menu-item"
                    role="menuitem"
                    onClick={() => {
                      setMoreMenuOpen(false);
                      onSettings();
                    }}
                  >
                    <span className="codicon codicon-settings-gear" />
                    <span>{t('common.settings', { defaultValue: 'Settings' })}</span>
                  </button>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
