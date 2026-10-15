import React, { useCallback, useEffect, useRef, useState } from 'react';
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
  onExportTab: (tab: SessionTabData) => void;
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
  onExportTab,
  onShareTab,
  onCopyShareLinkTab,
  onUnshareTab,
  t,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const overflowBtnRef = useRef<HTMLButtonElement>(null);
  const overflowMenuRef = useRef<HTMLDivElement>(null);
  const [editingTabId, setEditingTabId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState('');
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [overflowOpen, setOverflowOpen] = useState(false);
  const [menuPos, setMenuPos] = useState<{ x: number; y: number } | null>(null);
  const [visibleCount, setVisibleCount] = useState<number>(tabs.length);
  const editInputRef = useRef<HTMLInputElement>(null);
  const tabWidthsRef = useRef<Record<string, number>>({});

  // Enable horizontal scrolling with mouse wheel if needed
  const handleWheel = useCallback((e: React.WheelEvent<HTMLDivElement>) => {
    if (containerRef.current && e.deltaY !== 0) {
      containerRef.current.scrollLeft += e.deltaY;
    }
  }, []);

  // Calculate visible tabs based on container width
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const calculateVisibleTabs = () => {
      const containerW = el.clientWidth;
      if (containerW <= 0) return;
      const GAP = 4;
      const OVERFLOW_BTN_W = 32;

      let total = 0;
      for (let i = 0; i < tabs.length; i++) {
        const w = tabWidthsRef.current[tabs[i].id] || 110;
        total += (i > 0 ? GAP : 0) + w;
      }

      if (total <= containerW) {
        setVisibleCount(tabs.length);
        return;
      }

      const available = Math.max(60, containerW - OVERFLOW_BTN_W - GAP);
      let used = 0;
      let count = 0;
      for (let i = 0; i < tabs.length; i++) {
        const w = tabWidthsRef.current[tabs[i].id] || 110;
        if (used + (count > 0 ? GAP : 0) + w <= available) {
          used += (count > 0 ? GAP : 0) + w;
          count++;
        } else {
          break;
        }
      }
      setVisibleCount(Math.max(1, count));
    };

    calculateVisibleTabs();

    const ro = new ResizeObserver(() => {
      calculateVisibleTabs();
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [tabs]);

  // Close overflow menu on outside click or escape
  useEffect(() => {
    if (!overflowOpen) return;
    const handleMouseDown = (e: MouseEvent) => {
      const target = e.target as Node;
      if (
        overflowMenuRef.current &&
        !overflowMenuRef.current.contains(target) &&
        overflowBtnRef.current &&
        !overflowBtnRef.current.contains(target)
      ) {
        setOverflowOpen(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOverflowOpen(false);
      }
    };
    document.addEventListener('mousedown', handleMouseDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleMouseDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [overflowOpen]);

  // Compute visibleTabs and overflowTabs, ensuring active tab is visible if possible
  let visibleTabs: SessionTabData[] = tabs.slice(0, visibleCount);
  let overflowTabs: SessionTabData[] = tabs.slice(visibleCount);

  const activeInOverflowIdx = overflowTabs.findIndex((t) => t.id === activeTabId);
  if (activeInOverflowIdx >= 0 && visibleTabs.length > 0) {
    const activeTab = overflowTabs[activeInOverflowIdx];
    visibleTabs = [...visibleTabs.slice(0, visibleTabs.length - 1), activeTab];
    overflowTabs = tabs.filter((t) => !visibleTabs.some((vt) => vt.id === t.id));
  }

  const hasOverflow = overflowTabs.length > 0;
  const isAnyOverflowBusy = overflowTabs.some((t) => t.streamingActive || t.loading);
  const isAnyOverflowAttention = overflowTabs.some(
    (t) => (t.pendingPermissions?.length ?? 0) > 0 || (t.pendingQuestions?.length ?? 0) > 0
  );

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
        {visibleTabs.map((tab) => {
          const isActive = tab.id === activeTabId;
          const isEditing = editingTabId === tab.id;
          const isBusy = tab.streamingActive || tab.loading;
          const hasPendingAction =
            (tab.pendingPermissions?.length ?? 0) > 0 || (tab.pendingQuestions?.length ?? 0) > 0;

          return (
            <div
              key={tab.id}
              ref={(el) => {
                if (el) {
                  const w = el.getBoundingClientRect().width;
                  if (w > 0 && tabWidthsRef.current[tab.id] !== w) {
                    tabWidthsRef.current[tab.id] = w;
                  }
                }
              }}
              className={`session-tab-item ${isActive ? 'active' : ''} ${hasPendingAction ? 'has-attention' : ''}`}
              onClick={() => onSelectTab(tab.id)}
              onContextMenu={(e) => handleContextMenu(e, tab)}
              title={hasPendingAction ? `${tab.title} (${t('common.pendingApproval', { defaultValue: '等待操作' })})` : tab.title}
            >
              <div className="session-tab-icon">
                {hasPendingAction ? (
                  <span
                    className="codicon codicon-warning session-tab-attention-icon"
                    title={t('common.pendingApproval', { defaultValue: '等待操作' })}
                  />
                ) : isBusy ? (
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

        {hasOverflow && (
          <div className="session-tab-overflow-wrapper">
            <button
              ref={overflowBtnRef}
              className={`session-tab-overflow-btn ${overflowOpen ? 'active' : ''}`}
              onClick={(e) => {
                e.stopPropagation();
                if (!overflowOpen && overflowBtnRef.current) {
                  const rect = overflowBtnRef.current.getBoundingClientRect();
                  setMenuPos({ x: rect.left, y: rect.bottom + 4 });
                }
                setOverflowOpen((prev) => !prev);
              }}
              title={t('common.moreSessions', { defaultValue: '更多会话' })}
              aria-label={t('common.moreSessions', { defaultValue: '更多会话' })}
              aria-expanded={overflowOpen}
            >
              <span className="codicon codicon-ellipsis" />
              {isAnyOverflowAttention ? (
                <span className="session-tab-attention-dot" />
              ) : isAnyOverflowBusy ? (
                <span className="session-tab-busy-dot" />
              ) : null}
            </button>

            {overflowOpen && menuPos && (
              <div
                ref={overflowMenuRef}
                className="session-tab-overflow-menu"
                style={{
                  position: 'fixed',
                  left: `${Math.max(8, Math.min(menuPos.x, window.innerWidth - 220))}px`,
                  top: `${menuPos.y}px`,
                  zIndex: 10000,
                }}
                role="menu"
              >
                {overflowTabs.map((tab) => {
                  const isActive = tab.id === activeTabId;
                  const isBusy = tab.streamingActive || tab.loading;
                  const hasPendingAction =
                    (tab.pendingPermissions?.length ?? 0) > 0 || (tab.pendingQuestions?.length ?? 0) > 0;

                  return (
                    <div
                      key={tab.id}
                      className={`session-tab-overflow-item ${isActive ? 'active' : ''} ${hasPendingAction ? 'has-attention' : ''}`}
                      onClick={() => {
                        setOverflowOpen(false);
                        onSelectTab(tab.id);
                      }}
                      onContextMenu={(e) => {
                        setOverflowOpen(false);
                        handleContextMenu(e, tab);
                      }}
                      title={hasPendingAction ? `${tab.title} (${t('common.pendingApproval', { defaultValue: '等待操作' })})` : tab.title}
                      role="menuitem"
                    >
                      <div className="session-tab-icon">
                        {hasPendingAction ? (
                          <span
                            className="codicon codicon-warning session-tab-attention-icon"
                            title={t('common.pendingApproval', { defaultValue: '等待操作' })}
                          />
                        ) : isBusy ? (
                          <span className="codicon codicon-loading codicon-modifier-spin" />
                        ) : (
                          <span className="codicon codicon-comment-discussion" />
                        )}
                      </div>
                      <span className="session-tab-overflow-title">{tab.title}</span>
                      <button
                        className="session-tab-overflow-close"
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
            )}
          </div>
        )}
      </div>

      {contextMenu && (
        <SessionTabContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          tab={contextMenu.tab}
          onClose={() => setContextMenu(null)}
          onRename={() => startEditing(contextMenu.tab)}
          onFork={() => onForkTab(contextMenu.tab)}
          onExport={() => onExportTab(contextMenu.tab)}
          onShare={() => onShareTab(contextMenu.tab)}
          onCopyShareLink={() => onCopyShareLinkTab(contextMenu.tab)}
          onUnshare={() => onUnshareTab(contextMenu.tab)}
          t={t}
        />
      )}
    </>
  );
};
