import React, { useCallback, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import type { SessionTabData } from './types';
import { SessionTabContextMenu } from './SessionTabContextMenu';

export interface SessionTabBarProps {
  tabs: SessionTabData[];
  activeTabId: string;
  onSelectTab: (tabId: string) => void;
  onCloseTab: (tabId: string) => void;
  onRenameTab: (tabId: string, newTitle: string) => void;
  onForkTab: (tab: SessionTabData) => void;
  onShareTab: (tab: SessionTabData) => void;
  onCopyShareLinkTab: (tab: SessionTabData) => void;
  onUnshareTab: (tab: SessionTabData) => void;
  t: TFunction;
}

interface ContextMenuState {
  x: number;
  y: number;
  tab: SessionTabData;
}

export const SessionTabBar: React.FC<SessionTabBarProps> = ({
  tabs,
  activeTabId,
  onSelectTab,
  onCloseTab,
  onRenameTab,
  onForkTab,
  onShareTab,
  onCopyShareLinkTab,
  onUnshareTab,
  t,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [editingTabId, setEditingTabId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const editInputRef = useRef<HTMLInputElement>(null);

  // Enable horizontal scrolling with mouse wheel
  const handleWheel = useCallback((e: React.WheelEvent<HTMLDivElement>) => {
    if (containerRef.current && e.deltaY !== 0) {
      containerRef.current.scrollLeft += e.deltaY;
    }
  }, []);

  const startEditing = useCallback((tab: SessionTabData) => {
    setEditingTabId(tab.id);
    setEditTitle(tab.title);
    setTimeout(() => {
      if (editInputRef.current) {
        editInputRef.current.focus();
        editInputRef.current.select();
      }
    }, 20);
  }, []);

  const commitEditing = useCallback(
    (tabId: string) => {
      const trimmed = editTitle.trim();
      if (trimmed) {
        onRenameTab(tabId, trimmed);
      }
      setEditingTabId(null);
    },
    [editTitle, onRenameTab]
  );

  const cancelEditing = useCallback(() => {
    setEditingTabId(null);
  }, []);

  const handleContextMenu = useCallback((e: React.MouseEvent, tab: SessionTabData) => {
    e.preventDefault();
    e.stopPropagation();
    setContextMenu({
      x: e.clientX,
      y: e.clientY,
      tab,
    });
  }, []);

  return (
    <>
      <div className="session-tabs-container" ref={containerRef} onWheel={handleWheel}>
        {tabs.map((tab) => {
          const isActive = tab.id === activeTabId;
          const isEditing = editingTabId === tab.id;
          const isBusy = tab.streamingActive || tab.loading;

          return (
            <div
              key={tab.id}
              className={`session-tab-item ${isActive ? 'active' : ''}`}
              onClick={() => onSelectTab(tab.id)}
              onContextMenu={(e) => handleContextMenu(e, tab)}
              title={tab.title}
            >
              <div className="session-tab-icon">
                {isBusy ? (
                  <span className="codicon codicon-loading codicon-modifier-spin" />
                ) : (
                  <span className="codicon codicon-comment-discussion" />
                )}
              </div>

              {isEditing ? (
                <input
                  ref={editInputRef}
                  className="session-tab-inline-input"
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      commitEditing(tab.id);
                    } else if (e.key === 'Escape') {
                      e.preventDefault();
                      cancelEditing();
                    }
                  }}
                  onBlur={() => commitEditing(tab.id)}
                  onClick={(e) => e.stopPropagation()}
                  maxLength={50}
                  spellCheck={false}
                />
              ) : (
                <span
                  className="session-tab-title"
                  onDoubleClick={(e) => {
                    e.stopPropagation();
                    startEditing(tab);
                  }}
                >
                  {tab.title}
                </span>
              )}

              <button
                className="session-tab-close-btn"
                onClick={(e) => {
                  e.stopPropagation();
                  onCloseTab(tab.id);
                }}
                title={t('common.close', { defaultValue: 'Close' })}
                aria-label={t('common.close', { defaultValue: 'Close' })}
              >
                <span className="codicon codicon-close" />
              </button>
            </div>
          );
        })}
      </div>

      {contextMenu && (
        <SessionTabContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          tab={contextMenu.tab}
          onClose={() => setContextMenu(null)}
          onRename={() => startEditing(contextMenu.tab)}
          onFork={() => onForkTab(contextMenu.tab)}
          onShare={() => onShareTab(contextMenu.tab)}
          onCopyShareLink={() => onCopyShareLinkTab(contextMenu.tab)}
          onUnshare={() => onUnshareTab(contextMenu.tab)}
          t={t}
        />
      )}
    </>
  );
};
